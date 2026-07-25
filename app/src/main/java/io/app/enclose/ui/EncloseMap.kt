package io.app.enclose.ui

import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.app.enclose.data.MapCamera
import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.theme.EncloseAccents
import io.app.enclose.ui.theme.LocalEncloseAccents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.android.geometry.LatLngBounds
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

/** Free OpenStreetMap vector styles — no API key required. */
private const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

/**
 * Which basemap to draw. Defaults to [SYSTEM] (follow light/dark), but the map
 * has its own toggle: the dark basemap is hard to read in bright sunlight or
 * when you're looking for street detail, and that's independent of whether the
 * user wants a dark *app*.
 */
enum class BasemapStyle {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Resolves to an actual basemap, given the current system theme. */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }
}

private const val SRC_CLAIMED = "src-claimed"
private const val SRC_PATH = "src-path"
private const val SRC_START = "src-start"
private const val LYR_CLAIMED_FILL = "lyr-claimed-fill"
private const val LYR_CLAIMED_LINE = "lyr-claimed-line"
private const val SRC_CLOSE_ZONE = "src-close-zone"
private const val LYR_CLOSE_ZONE_FILL = "lyr-close-zone-fill"
private const val LYR_CLOSE_ZONE_LINE = "lyr-close-zone-line"
private const val LYR_PATH_CASING = "lyr-path-casing"
private const val LYR_PATH = "lyr-path"
private const val LYR_START = "lyr-start"

/** Holds references to the GeoJSON sources so overlays can be updated cheaply. */
private class Overlays(
    val claimed: GeoJsonSource,
    val closeZone: GeoJsonSource,
    val path: GeoJsonSource,
    val start: GeoJsonSource,
)

/**
 * Imperative handle on the map, owned by the caller.
 *
 * This replaces the previous "bump an Int to trigger a camera move" parameters
 * (`recenterTrigger`, `focusTrigger`), which silently did nothing before the map
 * finished loading and could not report success. [isStyleLoaded] and
 * [canLocate] let the UI disable controls that aren't usable yet instead of
 * offering buttons that no-op.
 */
@Stable
class MapController {
    internal var map: MapLibreMap? by mutableStateOf(null)
    internal var scope: CoroutineScope? = null

    /** True once the basemap style is up; the map is blank before this. */
    var isStyleLoaded: Boolean by mutableStateOf(false)
        internal set

    /** True once the "you are here" component is live and can be followed. */
    var canLocate: Boolean by mutableStateOf(false)
        internal set

    /** Animate to the user's position, waiting briefly for a first GPS fix. */
    fun recenter() {
        val m = map ?: return
        scope?.launch { flyToUser(m) }
    }

    /** Zoom by whole-ish steps from the zoom controls. */
    fun zoomBy(delta: Double) {
        val m = map ?: return
        runCatching { m.animateCamera(CameraUpdateFactory.zoomBy(delta), ZOOM_BUTTON_ANIM_MS) }
    }

    /** Frame a set of points, e.g. a territory selected from the list. */
    fun fitTo(points: List<LatLng>) {
        val m = map ?: return
        fitToPoints(m, points)
    }
}

@Composable
fun rememberMapController(): MapController {
    val controller = remember { MapController() }
    val scope = rememberCoroutineScope()
    controller.scope = scope
    return controller
}

/**
 * MapLibre map with overlays driven by app state:
 *  - claimed territories (filled polygons),
 *  - the closing zone around the walk's start,
 *  - the live walk path (line, with a casing so it reads over any basemap),
 *  - the walk's start anchor (dot).
 * The user's own position is shown via MapLibre's LocationComponent.
 */
@Composable
fun EncloseMap(
    walk: TrackingManager.WalkState,
    territories: List<Territory>,
    hasLocationPermission: Boolean,
    controller: MapController,
    modifier: Modifier = Modifier,
    /** When non-null, map taps are forwarded here (test mode) and consumed. */
    onMapTap: ((LatLng) -> Unit)? = null,
    /** Space (px) reserved at the bottom by UI, so map ornaments clear it. */
    bottomInsetPx: Int = 0,
    /** Space (px) reserved at the top by UI, so the compass clears it. */
    topInsetPx: Int = 0,
    /** Which basemap to draw; changing it swaps the style in place. */
    basemap: BasemapStyle = BasemapStyle.SYSTEM,
    /** Where to open the map, or null to start on the world view. */
    initialCamera: MapCamera? = null,
    /** Called when the camera settles, so the framing can be remembered. */
    onCameraIdle: (MapCamera) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    // The listener below is registered once, so it has to read the latest value
    // rather than close over the one this composition happened to start with.
    val restoredCamera by rememberUpdatedState(initialCamera)
    val accents = LocalEncloseAccents.current
    val styleUrl = if (basemap.isDark(isSystemInDarkTheme())) STYLE_URL_DARK else STYLE_URL_LIGHT

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var overlays by remember { mutableStateOf<Overlays?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // A restored camera counts as the initial focus already having happened:
    // flying to the user a second later would throw away the framing the user
    // themselves chose, which is the whole point of remembering it.
    var didInitialFocus by remember { mutableStateOf(initialCamera != null) }

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
            // Cancel the location component's animators before tearing down the
            // map; otherwise a pending frame can touch an invalidated style and
            // crash on the main thread.
            runCatching {
                controller.map?.locationComponent
                    ?.takeIf { it.isLocationComponentActivated }
                    ?.let { it.isLocationComponentEnabled = false }
            }
            controller.map = null
            controller.isStyleLoaded = false
            controller.canLocate = false
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (controller.map == null) {
                view.getMapAsync { mlMap ->
                    controller.map = mlMap
                    // Open where the user left off. Without a saved camera this
                    // is the world view, which the first GPS fix then replaces.
                    val saved = restoredCamera
                    mlMap.cameraPosition = if (saved != null) {
                        CameraPosition.Builder()
                            .target(MlLatLng(saved.lat, saved.lng))
                            .zoom(saved.zoom)
                            .bearing(saved.bearing)
                            .tilt(saved.tilt)
                            .build()
                    } else {
                        CameraPosition.Builder()
                            .target(MlLatLng(WORLD_LAT, WORLD_LNG))
                            .zoom(WORLD_ZOOM)
                            .build()
                    }
                    // Fires once movement settles, however it was caused —
                    // gesture, zoom button, wheel, or a programmatic fly-to.
                    mlMap.addOnCameraIdleListener {
                        val position = mlMap.cameraPosition
                        val target = position.target
                        if (target != null) {
                            currentOnCameraIdle(
                                MapCamera(
                                    lat = target.latitude,
                                    lng = target.longitude,
                                    zoom = position.zoom,
                                    bearing = position.bearing,
                                    tilt = position.tilt,
                                ),
                            )
                        }
                    }
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
                }
            }
        },
    )

    // (Re)load the basemap style. Keyed on the URL so an in-session light/dark
    // change swaps the basemap instead of leaving a dark map in a light app.
    LaunchedEffect(controller.map, styleUrl) {
        val m = controller.map ?: return@LaunchedEffect
        controller.isStyleLoaded = false
        overlays = null
        m.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            overlays = installOverlays(loaded, accents)
            style = loaded
            controller.isStyleLoaded = true
        }
    }

    // Keep the map's own ornaments (logo, attribution, compass) clear of the
    // floating UI. Attribution is a license requirement, so it must stay visible.
    // Margins are in pixels, so the dp values are converted for this display.
    val density = LocalDensity.current
    val marginPx = with(density) { ORNAMENT_MARGIN.roundToPx() }
    val logoWidthPx = with(density) { LOGO_WIDTH.roundToPx() }
    LaunchedEffect(controller.map, bottomInsetPx, topInsetPx, marginPx) {
        val m = controller.map ?: return@LaunchedEffect
        runCatching {
            m.uiSettings.apply {
                setLogoMargins(marginPx, 0, 0, bottomInsetPx + marginPx)
                setAttributionMargins(marginPx + logoWidthPx, 0, 0, bottomInsetPx + marginPx)
                setCompassMargins(0, topInsetPx + marginPx, marginPx, 0)
            }
        }
    }

    // Turn on the "you are here" dot as soon as the map is ready and we have
    // permission — whether it was granted before or after the map loaded. Also
    // re-runs after a style swap, which the location component requires.
    LaunchedEffect(controller.map, style, hasLocationPermission) {
        val m = controller.map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        val ok = runCatching { enableUserLocation(m, s, context) }.isSuccess
        controller.canLocate = ok
    }

    // Focus the camera on the user once the first GPS fix is acquired (once).
    LaunchedEffect(controller.canLocate) {
        if (!controller.canLocate || didInitialFocus) return@LaunchedEffect
        val m = controller.map ?: return@LaunchedEffect
        if (flyToUser(m)) didInitialFocus = true
    }

    // Re-center on the walker each time a walk starts, so it begins framed on them.
    LaunchedEffect(walk.isTracking) {
        if (!walk.isTracking) return@LaunchedEffect
        val m = controller.map ?: return@LaunchedEffect
        flyToUser(m)
    }

    // Redraw overlays whenever the walk or the claimed set changes.
    LaunchedEffect(overlays, walk, territories) {
        val o = overlays ?: return@LaunchedEffect
        o.claimed.setGeoJson(territoriesToFeatures(territories))
        o.closeZone.setGeoJson(closeZoneFeature(walk, accents))
        o.path.setGeoJson(pathToFeature(walk.path))
        o.start.setGeoJson(pointToFeature(walk.start))
    }
}

@SuppressLint("MissingPermission") // Only called after canLocate (permission checked).
private fun lastKnownLocation(map: MapLibreMap): MlLatLng? {
    val loc = runCatching { map.locationComponent.lastKnownLocation }.getOrNull() ?: return null
    return MlLatLng(loc.latitude, loc.longitude)
}

/** Animate the camera to frame a set of points (e.g. a claimed territory). */
private fun fitToPoints(map: MapLibreMap, points: List<LatLng>) {
    runCatching {
        when {
            points.size >= 2 -> {
                val builder = LatLngBounds.Builder()
                points.forEach { builder.include(MlLatLng(it.lat, it.lng)) }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(builder.build(), FIT_PADDING_PX),
                    FIT_ANIM_MS,
                )
            }
            points.size == 1 -> {
                val p = points.first()
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(MlLatLng(p.lat, p.lng), FOCUS_ZOOM),
                    FIT_ANIM_MS,
                )
            }
        }
    }
}

/**
 * Poll briefly for a GPS fix and animate the camera to it at street-level zoom.
 * Returns true once it has focused, false if no fix arrived in time.
 */
private suspend fun flyToUser(map: MapLibreMap): Boolean {
    repeat(FOCUS_POLL_ATTEMPTS) {
        val loc = lastKnownLocation(map)
        if (loc != null) {
            runCatching {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, FOCUS_ZOOM), FOCUS_ANIM_MS)
            }
            return true
        }
        delay(FOCUS_POLL_INTERVAL_MS)
    }
    return false
}

private fun installOverlays(style: Style, accents: EncloseAccents): Overlays {
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
            PropertyFactory.fillOpacity(0.32f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLAIMED_LINE, SRC_CLAIMED).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineJoin("round"),
        ),
    )
    // Closing zone around the start (drawn under the live path).
    style.addLayer(
        FillLayer(LYR_CLOSE_ZONE_FILL, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.14f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_CLOSE_ZONE_LINE, SRC_CLOSE_ZONE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    // Casing under the trail: keeps the amber line legible over both pale
    // pavement and dark parkland.
    style.addLayer(
        LineLayer(LYR_PATH_CASING, SRC_PATH).withProperties(
            PropertyFactory.lineColor(accents.trail.toHexString()),
            PropertyFactory.lineOpacity(0.28f),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        LineLayer(LYR_PATH, SRC_PATH).withProperties(
            PropertyFactory.lineColor(accents.trail.toHexString()),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        CircleLayer(LYR_START, SRC_START).withProperties(
            PropertyFactory.circleColor(accents.anchor.toHexString()),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
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
    // NORMAL (plain dot), NOT COMPASS: the compass-bearing animator keeps ticking
    // during map teardown and calls getSourceAs on an invalidated style → crash.
    component.renderMode = RenderMode.NORMAL
}

// Opening view before anything has been saved and before the first GPS fix.
private const val WORLD_LAT = 20.0
private const val WORLD_LNG = 0.0
private const val WORLD_ZOOM = 2.0

private const val FOCUS_ZOOM = 16.0
private const val FOCUS_ANIM_MS = 1200
private const val FOCUS_POLL_ATTEMPTS = 60
private const val FOCUS_POLL_INTERVAL_MS = 500L

// Mouse-wheel zoom: zoom levels changed per wheel notch, and the animation time.
private const val ZOOM_STEP = 0.6
private const val ZOOM_ANIM_MS = 120

// On-screen zoom buttons.
internal const val ZOOM_BUTTON_STEP = 1.0
private const val ZOOM_BUTTON_ANIM_MS = 220

// Framing a selected territory.
private const val FIT_PADDING_PX = 140
private const val FIT_ANIM_MS = 800

// Map ornament placement: the attribution (ⓘ) is offset past the logo so the two
// don't overlap in the bottom-left corner. The MapLibre logo asset is 88dp wide
// (maplibre_logo_icon is 88x23 at mdpi) — anything less than that pushed the ⓘ on
// top of the wordmark. 4dp of clearance follows, matching MapLibre's own default
// offset of 92dp. The attribution must stay visible and tappable: it carries the
// OpenStreetMap data credit, so it can't simply be hidden.
private val ORNAMENT_MARGIN = 12.dp
private val LOGO_WIDTH = 88.dp + 4.dp

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

/** The closing zone around the start, shown only while tracking. */
private fun closeZoneFeature(
    walk: TrackingManager.WalkState,
    accents: EncloseAccents,
): FeatureCollection {
    val start = walk.start
    if (!walk.isTracking || start == null) {
        return FeatureCollection.fromFeatures(emptyList())
    }
    val ring = Geo.circlePolygon(start, TrackingManager.closureRadiusMeters)
        .map(::point)
        .toMutableList()
        .apply { add(first()) }
    val color = if (walk.canCloseLoop) accents.zoneReady else accents.zoneWaiting
    val feature = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
        addStringProperty("color", color.toHexString())
    }
    return FeatureCollection.fromFeatures(listOf(feature))
}
