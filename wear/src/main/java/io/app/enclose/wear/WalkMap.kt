package io.app.enclose.wear

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.app.enclose.watchlink.GeoPoint
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchGeo
import io.app.enclose.watchlink.WatchMap
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The live walk on a real map: the same OpenFreeMap streets as the phone (its
 * dark style — a watch face is black), the path in the phone's trail amber, the
 * start and its closing circle, and the claims around it.
 *
 * Touch gestures are off: on Wear OS a swipe is "go back", and a map that eats
 * it traps the user. The crown zooms instead, and the camera follows the
 * walker, which is the only place on the map worth looking mid-walk.
 */
@Composable
fun WalkMap(
    map: WatchMap?,
    claims: WatchClaims?,
    readyToClose: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        // Must run before any MapView exists; repeat calls return the instance.
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var mlMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val focus = remember { FocusRequester() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier
            .onRotaryScrollEvent { event ->
                // Crown up zooms in. A notch is ~40 px; half a zoom level per
                // notch crosses street to neighbourhood in a few turns.
                mlMap?.animateCamera(
                    CameraUpdateFactory.zoomBy(-event.verticalScrollPixels / CROWN_PX_PER_ZOOM.toDouble()),
                    CROWN_ANIM_MS,
                )
                true
            }
            .focusRequester(focus)
            .focusable(),
        factory = {
            mapView.apply {
                getMapAsync { m ->
                    mlMap = m
                    m.uiSettings.apply {
                        setAllGesturesEnabled(false)
                        // Both sit in the corners, which a round screen doesn't
                        // have; the attribution is drawn by WalkScreen instead.
                        isLogoEnabled = false
                        isAttributionEnabled = false
                        isCompassEnabled = false
                    }
                    m.cameraPosition = CameraPosition.Builder().zoom(DEFAULT_ZOOM).build()
                    m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                        addOverlays(loaded)
                        style = loaded
                    }
                }
            }
        },
    )

    // The crown goes to whatever is focused, so the map asks for it.
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(style, claims) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_CLAIMS)?.setGeoJson(claimsFeatures(claims))
    }

    LaunchedEffect(style, map, readyToClose) {
        val s = style ?: return@LaunchedEffect
        val m = mlMap ?: return@LaunchedEffect
        val walk = map ?: WatchMap()
        s.getSourceAs<GeoJsonSource>(SRC_PATH)?.setGeoJson(
            if (walk.path.size >= 2) FeatureCollection.fromFeature(
                Feature.fromGeometry(LineString.fromLngLats(walk.path.map(::point))),
            ) else FeatureCollection.fromFeatures(emptyList()),
        )
        s.getSourceAs<GeoJsonSource>(SRC_START)?.setGeoJson(pointFeatures(walk.start))
        s.getSourceAs<GeoJsonSource>(SRC_HERE)?.setGeoJson(pointFeatures(walk.current))
        s.getSourceAs<GeoJsonSource>(SRC_CLOSE_ZONE)?.setGeoJson(
            walk.start?.takeIf { walk.closeRadiusMeters > 0 }?.let { start ->
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        Polygon.fromLngLats(listOf(WatchGeo.circle(start, walk.closeRadiusMeters).map(::point))),
                    ).apply { addBooleanProperty("ready", readyToClose) },
                )
            } ?: FeatureCollection.fromFeatures(emptyList()),
        )
        // Follow the walker; before the first position, the start; before that,
        // nothing to follow yet.
        (walk.current ?: walk.start)?.let { target ->
            // The lower part of the circle is the figures strip, so "centre" is
            // pushed up into the part of the map that shows: the walker sits
            // above the strip, not under it. Set here, not when the map loads,
            // because the view has a height by now and may not have had then.
            m.setPadding(0, 0, 0, (mapView.height * STRIP_FRACTION).toInt())
            m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lng)), FOLLOW_ANIM_MS)
        }
    }
}

private fun addOverlays(style: Style) {
    listOf(SRC_CLAIMS, SRC_CLOSE_ZONE, SRC_PATH, SRC_START, SRC_HERE)
        .forEach { style.addSource(GeoJsonSource(it)) }

    // Claims, as on the phone: their own colour, translucent, outlined.
    style.addLayer(
        FillLayer(LYR_CLAIMS_FILL, SRC_CLAIMS).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.32f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLAIMS_LINE, SRC_CLAIMS).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2f),
        ),
    )
    // The closing circle turns from amber to the brand magenta once standing in
    // it would claim — the same moment the wrist buzzes.
    val zoneColor = Expression.switchCase(
        Expression.get("ready"),
        Expression.toColor(Expression.literal(READY_COLOR)),
        Expression.toColor(Expression.literal(TRAIL_COLOR)),
    )
    style.addLayer(
        FillLayer(LYR_CLOSE_FILL, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.fillColor(zoneColor),
            PropertyFactory.fillOpacity(0.25f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLOSE_LINE, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.lineColor(zoneColor),
            PropertyFactory.lineWidth(1.5f),
        ),
    )
    // The trail, with a soft casing so it holds up over the dark basemap.
    style.addLayer(
        LineLayer(LYR_PATH_CASING, SRC_PATH).withProperties(
            PropertyFactory.lineColor(TRAIL_COLOR),
            PropertyFactory.lineOpacity(0.28f),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        LineLayer(LYR_PATH, SRC_PATH).withProperties(
            PropertyFactory.lineColor(TRAIL_COLOR),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        CircleLayer(LYR_START, SRC_START).withProperties(
            PropertyFactory.circleColor(TRAIL_COLOR),
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    // The walker, in the app's violet so it can't be mistaken for the start.
    style.addLayer(
        CircleLayer(LYR_HERE, SRC_HERE).withProperties(
            PropertyFactory.circleColor(HERE_COLOR),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

private fun claimsFeatures(claims: WatchClaims?): FeatureCollection =
    FeatureCollection.fromFeatures(
        claims?.claims.orEmpty().flatMap { claim ->
            val color = String.format("#%06X", claim.color and 0xFFFFFF)
            claim.polygons.mapNotNull { polygon ->
                // GeoJSON rings must close; the phone's are implicitly closed.
                val rings = polygon.filter { it.size >= 3 }.map { ring -> (ring + ring.first()).map(::point) }
                if (rings.isEmpty()) null
                else Feature.fromGeometry(Polygon.fromLngLats(rings)).apply { addStringProperty("color", color) }
            }
        },
    )

private fun pointFeatures(p: GeoPoint?): FeatureCollection =
    p?.let { FeatureCollection.fromFeature(Feature.fromGeometry(point(it))) }
        ?: FeatureCollection.fromFeatures(emptyList())

private fun point(p: GeoPoint): Point = Point.fromLngLat(p.lng, p.lat)

/** The phone's dark basemap. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** The phone's dark-theme trail colour (BrandAmber). */
private const val TRAIL_COLOR = "#F2A65A"
private const val READY_COLOR = "#DD59F8"
private const val HERE_COLOR = "#AE4FF8"

/** A few blocks across a 1.4" screen: enough to see the loop round to the start. */
private const val DEFAULT_ZOOM = 15.5

/** How much of the screen's height the figures strip covers, from the bottom. */
private const val STRIP_FRACTION = 0.4f
private const val CROWN_PX_PER_ZOOM = 80f
private const val CROWN_ANIM_MS = 150
private const val FOLLOW_ANIM_MS = 800

private const val SRC_CLAIMS = "src-claims"
private const val SRC_CLOSE_ZONE = "src-close-zone"
private const val SRC_PATH = "src-path"
private const val SRC_START = "src-start"
private const val SRC_HERE = "src-here"
private const val LYR_CLAIMS_FILL = "lyr-claims-fill"
private const val LYR_CLAIMS_LINE = "lyr-claims-line"
private const val LYR_CLOSE_FILL = "lyr-close-fill"
private const val LYR_CLOSE_LINE = "lyr-close-line"
private const val LYR_PATH_CASING = "lyr-path-casing"
private const val LYR_PATH = "lyr-path"
private const val LYR_START = "lyr-start"
private const val LYR_HERE = "lyr-here"
