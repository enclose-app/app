package io.app.enclose.ui

import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.TrackingManager
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
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
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Free OpenStreetMap vector style — no API key required. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SRC_CLAIMED = "src-claimed"
private const val SRC_PATH = "src-path"
private const val SRC_START = "src-start"
private const val LYR_CLAIMED_FILL = "lyr-claimed-fill"
private const val LYR_CLAIMED_LINE = "lyr-claimed-line"
private const val SRC_CLOSE_ZONE = "src-close-zone"
private const val LYR_CLOSE_ZONE_FILL = "lyr-close-zone-fill"
private const val LYR_CLOSE_ZONE_LINE = "lyr-close-zone-line"
private const val LYR_PATH = "lyr-path"
private const val LYR_START = "lyr-start"

private const val PATH_COLOR = "#F2A65A"
private const val START_COLOR = "#F2A65A"
// Closing zone: purple once the loop can close, grey while conditions aren't met.
private const val ZONE_READY = "#7B1FA2"
private const val ZONE_WAITING = "#9E9E9E"

/** Holds references to the GeoJSON sources so overlays can be updated cheaply. */
private class Overlays(
    val claimed: GeoJsonSource,
    val closeZone: GeoJsonSource,
    val path: GeoJsonSource,
    val start: GeoJsonSource,
)

/**
 * MapLibre map with three overlays driven by app state:
 *  - claimed territories (filled polygons),
 *  - the live walk path (line),
 *  - the walk's start anchor (dot).
 * The user's own position is shown via MapLibre's LocationComponent.
 */
@Composable
fun EncloseMap(
    walk: TrackingManager.WalkState,
    territories: List<Territory>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    /** When non-null, map taps are forwarded here (test mode) and consumed. */
    onMapTap: ((LatLng) -> Unit)? = null,
    /** Bumping this value re-centers the camera on the user's position. */
    recenterTrigger: Int = 0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapTap by rememberUpdatedState(onMapTap)

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var overlays by remember { mutableStateOf<Overlays?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var locationEnabled by remember { mutableStateOf(false) }
    var didInitialFocus by remember { mutableStateOf(false) }

    // Forward Compose lifecycle to the MapView.
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
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (map == null) {
                view.getMapAsync { mlMap ->
                    map = mlMap
                    mlMap.cameraPosition = CameraPosition.Builder()
                        .target(MlLatLng(20.0, 0.0))
                        .zoom(2.0)
                        .build()
                    mlMap.addOnMapClickListener { point ->
                        val handler = currentOnMapTap
                        if (handler != null) {
                            handler(LatLng(point.latitude, point.longitude))
                            true // consume the tap in test mode
                        } else {
                            false
                        }
                    }
                    // Mouse-wheel zoom (emulator/desktop): zoom toward the cursor.
                    view.setOnGenericMotionListener { _, event ->
                        if (event.action == MotionEvent.ACTION_SCROLL &&
                            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                        ) {
                            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                            val focus = android.graphics.Point(event.x.toInt(), event.y.toInt())
                            mlMap.animateCamera(
                                CameraUpdateFactory.zoomBy(scroll.toDouble() * ZOOM_STEP, focus),
                                ZOOM_ANIM_MS,
                            )
                            true
                        } else {
                            false
                        }
                    }
                    mlMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                        overlays = installOverlays(loaded)
                        style = loaded
                    }
                }
            }
        },
    )

    // Turn on the "you are here" dot as soon as the map is ready and we have
    // permission — whether it was granted before or after the map loaded.
    LaunchedEffect(map, style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (hasLocationPermission && !locationEnabled) {
            enableUserLocation(m, s, context)
            locationEnabled = true
        }
    }

    // Focus the camera on the user once the first GPS fix is acquired (once).
    LaunchedEffect(locationEnabled) {
        if (!locationEnabled || didInitialFocus) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (flyToUser(m)) didInitialFocus = true
    }

    // Re-center on the walker each time a walk starts, so it begins framed on them.
    LaunchedEffect(walk.isTracking) {
        if (!walk.isTracking) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        flyToUser(m)
    }

    // Re-center on the user when the recenter button is pressed.
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger == 0) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (locationEnabled) flyToUser(m)
    }

    // Redraw overlays whenever the walk or the claimed set changes.
    LaunchedEffect(overlays, walk, territories) {
        val o = overlays ?: return@LaunchedEffect
        o.claimed.setGeoJson(territoriesToFeatures(territories))
        o.closeZone.setGeoJson(closeZoneFeature(walk))
        o.path.setGeoJson(pathToFeature(walk.path))
        o.start.setGeoJson(pointToFeature(walk.start))
    }
}

@SuppressLint("MissingPermission") // Only called after locationEnabled (permission checked).
private fun lastKnownLocation(map: MapLibreMap): MlLatLng? {
    val loc = map.locationComponent.lastKnownLocation ?: return null
    return MlLatLng(loc.latitude, loc.longitude)
}

/**
 * Poll briefly for a GPS fix and animate the camera to it at street-level zoom.
 * Returns true once it has focused, false if no fix arrived in time.
 */
private suspend fun flyToUser(map: MapLibreMap): Boolean {
    repeat(FOCUS_POLL_ATTEMPTS) {
        val loc = lastKnownLocation(map)
        if (loc != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, FOCUS_ZOOM), FOCUS_ANIM_MS)
            return true
        }
        delay(FOCUS_POLL_INTERVAL_MS)
    }
    return false
}

private fun installOverlays(style: Style): Overlays {
    val claimed = GeoJsonSource(SRC_CLAIMED)
    val closeZone = GeoJsonSource(SRC_CLOSE_ZONE)
    val path = GeoJsonSource(SRC_PATH)
    val start = GeoJsonSource(SRC_START)
    style.addSource(claimed)
    style.addSource(closeZone)
    style.addSource(path)
    style.addSource(start)

    style.addLayer(
        FillLayer(LYR_CLAIMED_FILL, SRC_CLAIMED).withProperties(
            // Per-feature color from the "color" property set in territoriesToFeatures.
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.35f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLAIMED_LINE, SRC_CLAIMED).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2.5f),
        ),
    )
    // Closing zone around the start (drawn under the live path).
    style.addLayer(
        FillLayer(LYR_CLOSE_ZONE_FILL, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.12f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLOSE_ZONE_LINE, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addLayer(
        LineLayer(LYR_PATH, SRC_PATH).withProperties(
            PropertyFactory.lineColor(PATH_COLOR),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        CircleLayer(LYR_START, SRC_START).withProperties(
            PropertyFactory.circleColor(START_COLOR),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    return Overlays(claimed, closeZone, path, start)
}

@SuppressLint("MissingPermission") // Caller guards on hasLocationPermission.
private fun enableUserLocation(map: MapLibreMap, style: Style, context: android.content.Context) {
    val component = map.locationComponent
    component.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style).build(),
    )
    component.isLocationComponentEnabled = true
    // Show the dot but leave the camera free after the initial focus, so the
    // user can pan/zoom the map without it snapping back.
    component.cameraMode = CameraMode.NONE
    component.renderMode = RenderMode.COMPASS
}

private const val FOCUS_ZOOM = 16.0
private const val FOCUS_ANIM_MS = 1200
private const val FOCUS_POLL_ATTEMPTS = 60
private const val FOCUS_POLL_INTERVAL_MS = 500L

// Mouse-wheel zoom: zoom levels changed per wheel notch, and the animation time.
private const val ZOOM_STEP = 0.6
private const val ZOOM_ANIM_MS = 120

// --- GeoJSON builders --------------------------------------------------------

private fun point(p: LatLng): Point = Point.fromLngLat(p.lng, p.lat)

private fun closedRing(ring: List<LatLng>): List<Point> {
    val pts = ring.map(::point).toMutableList()
    if (pts.size >= 3 && pts.first() != pts.last()) pts.add(pts.first())
    return pts
}

private fun territoriesToFeatures(territories: List<Territory>): FeatureCollection {
    val features = territories.mapNotNull { t ->
        // MultiPolygon: [ [ exterior, hole... ], ... ] — supports carved-out claims.
        val multi = t.polygons
            .map { poly -> poly.map { ring -> closedRing(ring) } }
            .filter { poly -> (poly.firstOrNull()?.size ?: 0) >= 4 }
        if (multi.isEmpty()) return@mapNotNull null
        Feature.fromGeometry(MultiPolygon.fromLngLats(multi)).apply {
            addStringProperty("color", t.colorHex)
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun pathToFeature(path: List<LatLng>): FeatureCollection {
    if (path.size < 2) return FeatureCollection.fromFeatures(emptyList())
    val line = LineString.fromLngLats(path.map(::point))
    return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line)))
}

private fun pointToFeature(p: LatLng?): FeatureCollection {
    if (p == null) return FeatureCollection.fromFeatures(emptyList())
    return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(point(p))))
}

/** The 60 m closing zone around the start, shown only while tracking. */
private fun closeZoneFeature(walk: TrackingManager.WalkState): FeatureCollection {
    val start = walk.start
    if (!walk.isTracking || start == null) {
        return FeatureCollection.fromFeatures(emptyList())
    }
    val ring = Geo.circlePolygon(start, TrackingManager.CLOSURE_RADIUS_METERS)
        .map(::point)
        .toMutableList()
        .apply { add(first()) }
    val color = if (walk.canCloseLoop) ZONE_READY else ZONE_WAITING
    val feature = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
        addStringProperty("color", color)
    }
    return FeatureCollection.fromFeatures(listOf(feature))
}
