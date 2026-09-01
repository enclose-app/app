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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.app.enclose.data.MapCamera
import io.app.enclose.data.SnapDisplay
import io.app.enclose.data.Territory
import io.app.enclose.geo.DistanceMarker
import io.app.enclose.geo.DistanceMarkers
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
import org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
 * The style the map would draw right now. The offline downloader needs the same
 * URL the map uses, and a worker has no composition to read it from.
 */
fun basemapStyleUrl(dark: Boolean): String = if (dark) STYLE_URL_DARK else STYLE_URL_LIGHT

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
private const val SRC_ROUTE = "src-route"
private const val LYR_ROUTE = "lyr-route"
private const val SRC_PATH = "src-path"
private const val SRC_START = "src-start"
private const val SRC_HOME = "src-home"
private const val IMG_HOME = "img-home"
private const val LYR_HOME = "lyr-home"
private const val LYR_CLAIMED_FILL = "lyr-claimed-fill"
private const val LYR_CLAIMED_LINE = "lyr-claimed-line"
private const val SRC_SELECTED = "src-selected"
private const val LYR_SELECTED_FILL = "lyr-selected-fill"
private const val LYR_SELECTED_CASING = "lyr-selected-casing"
private const val LYR_SELECTED_LINE = "lyr-selected-line"
private const val SRC_CLOSE_ZONE = "src-close-zone"
private const val LYR_CLOSE_ZONE_FILL = "lyr-close-zone-fill"
private const val LYR_CLOSE_ZONE_LINE = "lyr-close-zone-line"
private const val LYR_PATH_CASING = "lyr-path-casing"
private const val LYR_PATH = "lyr-path"
private const val LYR_START = "lyr-start"
private const val SRC_MILESTONES = "src-milestones"
private const val LYR_MILESTONES = "lyr-milestones"

/** Image id for the badge carrying the number [index]; one image per kilometre. */
private fun milestoneImageId(index: Int): String = "img-milestone-$index"

/** Holds references to the GeoJSON sources so overlays can be updated cheaply. */
private class Overlays(
    val claimed: GeoJsonSource,
    /** The one claim a map tap picked out, redrawn on top of the rest. */
    val selected: GeoJsonSource,
    val closeZone: GeoJsonSource,
    val route: GeoJsonSource,
    val path: GeoJsonSource,
    val start: GeoJsonSource,
    val home: GeoJsonSource,
    val milestones: GeoJsonSource,
) {
    /**
     * Highest kilometre badge registered on the style so far.
     *
     * Kept here rather than in composition state because it is a property of
     * *this style*: images belong to the style, and a basemap swap builds a new
     * one, taking every badge with it. Rebuilding [Overlays] is exactly the
     * moment the count has to go back to zero, so the two can't fall out of step.
     */
    var registeredMilestones: Int = 0

    /**
     * The (fill, label) colours those badges were drawn in.
     *
     * A badge is a bitmap, so unlike every other overlay here its colour is
     * baked in at the moment it is drawn — and the app's theme can change
     * *without* the style being rebuilt, which is what happens when the basemap
     * has been pinned to light or dark by hand. Without this the badges keep the
     * theme they were born in while the trail under them and the panel below
     * change, which is the exact drift [EncloseAccents] exists to prevent.
     */
    var milestoneColors: Pair<Int, Int>? = null
}

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

    /**
     * Whether the camera keeps up with the walker as fixes arrive.
     *
     * Turned on when a walk starts and by [recenter], and turned off the instant
     * the user pans the map themselves — a map that snaps back while you're
     * trying to look at the street ahead is unusable, and a walk map that
     * doesn't keep up with you is a picture of where you were. The recenter
     * button is how you get it back, which is also what it looks like it does.
     */
    var followUser: Boolean by mutableStateOf(false)
        internal set

    /** Animate to the user's position, waiting briefly for a first GPS fix. */
    fun recenter() {
        val m = map ?: return
        followUser = true
        scope?.launch { flyToUser(m) }
    }

    /**
     * Keep up with the walker without touching their zoom.
     *
     * Distinct from [flyTo], which frames a place at a fixed zoom: following is
     * about staying centred, and re-zooming every few seconds would take the
     * choice of how much ground to see away from the user.
     */
    internal fun panTo(point: LatLng) {
        val m = map ?: return
        runCatching {
            m.animateCamera(
                CameraUpdateFactory.newLatLng(MlLatLng(point.lat, point.lng)),
                FOLLOW_ANIM_MS,
            )
        }
    }

    /**
     * The last fix the map has, or null before one arrives. Read rather than
     * waited on: this answers "can I save where I'm standing right now?", and a
     * caller that has to poll for an answer would be holding a dialog open
     * while it did.
     */
    fun currentLocation(): LatLng? {
        val m = map ?: return null
        val loc = lastKnownLocation(m) ?: return null
        return LatLng(loc.latitude, loc.longitude)
    }

    /**
     * Where the user is *now*, or null if the newest fix is older than
     * [maxAgeMs].
     *
     * The last known location is not the same thing as where somebody is
     * standing. It survives across sessions, so an app opened indoors — or on a
     * device that has not had a fix since another city — hands out a position
     * that is confidently, precisely wrong. That is tolerable for framing a map
     * and not tolerable for planning a walk from: the route comes back drawn
     * around wherever the phone last saw sky, off screen, looking for all the
     * world like the feature is broken. (Found exactly this way on an emulator,
     * which answers with Mountain View until its first mock fix lands.)
     *
     * Aged by `elapsedRealtimeNanos` rather than by wall clock, for the reason
     * `LocationService` records: the wall clock can be stepped by the network
     * while the monotonic one cannot.
     */
    fun recentLocation(maxAgeMs: Long): LatLng? {
        val m = map ?: return null
        val loc = runCatching { rawLastKnownLocation(m) }.getOrNull() ?: return null
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) /
            1_000_000
        if (ageMs > maxAgeMs) return null
        return LatLng(loc.latitude, loc.longitude)
    }

    /**
     * Centre on a point, leaving the zoom exactly as the user set it.
     *
     * Distinct from [flyTo], which pulls the camera to a fixed street-level
     * zoom, and from [fitTo], which frames a whole shape: this is for a claim
     * the user has just tapped, where they have already chosen how much ground
     * they want to see and re-zooming would take that choice off them.
     *
     * [bottomInsetPx] is the UI covering the foot of the map — the panel and
     * the claim card. Without it the claim lands in the middle of the *window*,
     * which is below the middle of the map anyone can actually see. The shift
     * is measured through the live projection rather than guessed at in
     * degrees, since a pixel is worth a different number of degrees at every
     * zoom and latitude; that is exact here because the zoom does not change.
     */
    fun centerOn(point: LatLng, bottomInsetPx: Int = 0) {
        val m = map ?: return
        runCatching {
            val target = MlLatLng(point.lat, point.lng)
            val centre = if (bottomInsetPx > 0) {
                val screen = m.projection.toScreenLocation(target)
                m.projection.fromScreenLocation(
                    android.graphics.PointF(screen.x, screen.y + bottomInsetPx / 2f),
                )
            } else {
                target
            }
            m.animateCamera(CameraUpdateFactory.newLatLng(centre), CENTER_ANIM_MS)
        }
    }

    /** Animate to a fixed point (the saved home), at street-level zoom. */
    fun flyTo(point: LatLng) {
        val m = map ?: return
        runCatching {
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(MlLatLng(point.lat, point.lng), FOCUS_ZOOM),
                FOCUS_ANIM_MS,
            )
        }
    }

    /** Zoom by whole-ish steps from the zoom controls. */
    fun zoomBy(delta: Double) {
        val m = map ?: return
        runCatching { m.animateCamera(CameraUpdateFactory.zoomBy(delta), ZOOM_BUTTON_ANIM_MS) }
    }

    /**
     * Frame a set of points, e.g. a territory selected from the list.
     *
     * [bottomInsetPx] keeps the shape clear of something covering the lower part
     * of the screen — the route planner's sheet, which takes up half of it and
     * would otherwise sit on top of the very loop it is describing.
     */
    fun fitTo(points: List<LatLng>, bottomInsetPx: Int = 0) {
        val m = map ?: return
        // Clamped to half the map, because the inset is a real measurement and
        // the window is not always tall: a sheet that covers two thirds of a
        // landscape phone would otherwise leave a strip to fit a 5 km loop into,
        // and the camera answers that by zooming out to the next county.
        val usable = runCatching { m.height }.getOrDefault(0f)
        val inset = if (usable > 0f) {
            bottomInsetPx.coerceAtMost((usable * MAX_FIT_INSET_FRACTION).toInt())
        } else {
            bottomInsetPx
        }
        fitToPoints(m, points, inset)
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
    /**
     * The claim the user tapped, drawn brighter and outlined over the rest.
     * Null draws no highlight. It is expected to be one of [territories]; a
     * claim that is not in the list is still drawn, which is what keeps a
     * selection visible for the frame in which the list is being replaced.
     */
    selected: Territory? = null,
    hasLocationPermission: Boolean,
    controller: MapController,
    modifier: Modifier = Modifier,
    /** The saved home position; null draws no marker at all. */
    home: LatLng? = null,
    /**
     * A suggested route to follow, drawn faintly under everything else. Empty
     * draws nothing.
     *
     * Under, and faint, on purpose: this is the walk somebody was *offered*, and
     * the moment they set off it is the walked trail that matters. A route drawn
     * as boldly as the trail would leave them unable to see how far round they
     * had got.
     *
     * **While this is non-empty the claimed territories are not drawn at all** —
     * see the overlay effect below for why.
     */
    plannedRoute: List<LatLng> = emptyList(),
    /**
     * Whether the camera keeps up with the walker. False where the points are
     * coming from the user's own taps rather than from GPS: re-centring on each
     * one moves the map out from under the finger placing the next, which turns
     * a tapped loop into a spiral.
     */
    followWalker: Boolean = true,
    /**
     * Map taps, in map coordinates. Returning true consumes the tap.
     *
     * Two callers share this: test mode, which turns a tap into a walk point,
     * and the selection hit-test, which turns it into a claim. A tap that
     * neither wanted — open ground, no test walk — is handed back to the map
     * rather than swallowed.
     */
    onMapTap: ((LatLng) -> Boolean)? = null,
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
                    // A pan or a pinch means the user wants to look somewhere;
                    // following them around would fight the gesture that just
                    // happened. Only gestures count — the fly-to that following
                    // itself performs arrives here as an animation, and would
                    // otherwise switch following off on its first frame.
                    mlMap.addOnCameraMoveStartedListener { reason ->
                        if (reason == REASON_API_GESTURE) controller.followUser = false
                    }
                    mlMap.addOnMapClickListener { point ->
                        val handler = currentOnMapTap
                        handler?.invoke(LatLng(point.latitude, point.longitude)) ?: false
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
            overlays = installOverlays(loaded, accents, context)
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

    // Re-center on the walker each time a walk starts, so it begins framed on
    // them — and keep following until they pan the map themselves.
    LaunchedEffect(walk.isTracking, followWalker) {
        if (!walk.isTracking || !followWalker) return@LaunchedEffect
        val m = controller.map ?: return@LaunchedEffect
        controller.followUser = true
        flyToUser(m)
    }

    // Follow: each accepted fix re-centres the map, at whatever zoom the user is
    // on. Keyed on the position alone, so a change to a figure the map doesn't
    // draw doesn't move the camera.
    LaunchedEffect(walk.current, controller.followUser, controller.isStyleLoaded, followWalker) {
        val here = walk.current ?: return@LaunchedEffect
        if (followWalker && controller.followUser && controller.isStyleLoaded) controller.panTo(here)
    }

    // Redraw overlays whenever the walk or the claimed set changes. Keyed on the
    // style and the accents too, because the kilometre badges are images owned by
    // the style and painted from the theme: a basemap swap drops them, and a
    // theme change has to repaint them (see [Overlays.milestoneColors]).
    LaunchedEffect(overlays, style, accents, walk, territories, selected, plannedRoute) {
        val o = overlays ?: return@LaunchedEffect
        // **Claims stand down while a route is on the map.** A suggested route is
        // a line to follow through streets, and the claims are filled polygons
        // covering exactly the ground it runs across — read together they are
        // unreadable, and the one you need to see is the one you haven't walked
        // yet. They come back the moment the route goes, which includes the walk
        // ending: stopping clears the route (see EncloseViewModel), so the map a
        // walker returns to is the map of what they hold.
        //
        // Decided here rather than in the callers so the full screen and the
        // floating window can't disagree about it.
        val claims = if (plannedRoute.isEmpty()) territories else emptyList()
        o.claimed.setGeoJson(territoriesToFeatures(claims))
        // The highlight stands down with the claims themselves: with a route on
        // the map there is nothing drawn for it to be the selected one *of*.
        val highlighted = if (plannedRoute.isEmpty()) listOfNotNull(selected) else emptyList()
        o.selected.setGeoJson(territoriesToFeatures(highlighted))
        o.closeZone.setGeoJson(closeZoneFeature(walk, accents))
        o.path.setGeoJson(pathToFeature(walk.path))
        o.start.setGeoJson(pointToFeature(walk.start))

        // Kilometre badges along the trail. Recomputed from the whole path on
        // each fix rather than accumulated: the same walk state that draws the
        // line draws the marks on it, so the two can't disagree after a restore
        // from process death, where an accumulated count would come back empty
        // and start again at 1 halfway round the loop.
        val markers = DistanceMarkers.along(walk.path)
        val s = style
        if (s != null) ensureMilestoneImages(s, o, markers.size, accents, context)
        o.milestones.setGeoJson(milestonesToFeatures(markers))
    }

    // The suggested route changes only when one is accepted or cleared, so it
    // gets its own effect rather than being rebuilt on every fix — it is the
    // largest of these geometries and the least likely to have changed.
    LaunchedEffect(overlays, plannedRoute) {
        val o = overlays ?: return@LaunchedEffect
        o.route.setGeoJson(pathToFeature(plannedRoute))
    }

    // Home changes on its own schedule — it's set and reset from the button, not
    // by walking — so it gets its own effect rather than redrawing every overlay
    // on each GPS fix.
    LaunchedEffect(overlays, home) {
        val o = overlays ?: return@LaunchedEffect
        o.home.setGeoJson(pointToFeature(home))
    }
}

@SuppressLint("MissingPermission") // Only called after canLocate (permission checked).
private fun lastKnownLocation(map: MapLibreMap): MlLatLng? {
    val loc = rawLastKnownLocation(map) ?: return null
    return MlLatLng(loc.latitude, loc.longitude)
}

/** The fix itself, for callers that need its age as well as its position. */
@SuppressLint("MissingPermission") // Only called after canLocate (permission checked).
private fun rawLastKnownLocation(map: MapLibreMap): android.location.Location? =
    runCatching { map.locationComponent.lastKnownLocation }.getOrNull()

/** Animate the camera to frame a set of points (e.g. a claimed territory). */
private fun fitToPoints(map: MapLibreMap, points: List<LatLng>, bottomInsetPx: Int = 0) {
    runCatching {
        when {
            points.size >= 2 -> {
                val builder = LatLngBounds.Builder()
                points.forEach { builder.include(MlLatLng(it.lat, it.lng)) }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        builder.build(),
                        FIT_PADDING_PX,
                        FIT_PADDING_PX,
                        FIT_PADDING_PX,
                        FIT_PADDING_PX + bottomInsetPx,
                    ),
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

private fun installOverlays(
    style: Style,
    accents: EncloseAccents,
    context: android.content.Context,
): Overlays {
    val claimed = GeoJsonSource(SRC_CLAIMED)
    val selected = GeoJsonSource(SRC_SELECTED)
    val closeZone = GeoJsonSource(SRC_CLOSE_ZONE)
    val route = GeoJsonSource(SRC_ROUTE)
    val path = GeoJsonSource(SRC_PATH)
    val start = GeoJsonSource(SRC_START)
    val home = GeoJsonSource(SRC_HOME)
    val milestones = GeoJsonSource(SRC_MILESTONES)
    style.addSource(claimed)
    style.addSource(selected)
    style.addSource(closeZone)
    style.addSource(route)
    style.addSource(path)
    style.addSource(start)
    style.addSource(home)
    style.addSource(milestones)
    // Images belong to the style, so the marker is registered here rather than
    // once at startup — a light/dark swap builds a whole new style, and an image
    // added to the old one goes with it.
    style.addImage(IMG_HOME, homeMarkerBitmap(context, accents.home.toArgb()))

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
    // The tapped claim, drawn over the others in its own colour: a stronger
    // fill, and a white casing under a thicker outline so the edge reads
    // against neighbouring claims of a similar colour on either basemap. Kept
    // to the claim's own colour rather than a highlight colour of its own —
    // the card naming it is a few pixels away, and a claim that changes colour
    // when you touch it stops looking like the claim you touched.
    style.addLayer(
        FillLayer(LYR_SELECTED_FILL, SRC_SELECTED).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.5f),
        ),
    )
    style.addLayer(
        LineLayer(LYR_SELECTED_CASING, SRC_SELECTED).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineJoin("round"),
        ),
    )
    style.addLayer(
        LineLayer(LYR_SELECTED_LINE, SRC_SELECTED).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(3.5f),
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
    // The suggested route, under the trail and behind it in every sense: dashed
    // so it reads as "not walked yet" even where it runs along a street the map
    // has drawn in a similar colour, and half-transparent so the basemap's own
    // road names stay readable through it — somebody following this needs the
    // street names more than they need a bold line.
    style.addLayer(
        LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(accents.route.toHexString()),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineDasharray(arrayOf(1.6f, 1.1f)),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
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
    // Kilometre badges, above the trail they mark and below the start anchor —
    // the anchor is the one point on the map that has to stay findable, and a
    // loop that comes back on itself will drop a badge right on top of it.
    //
    // Overlap is allowed rather than left to the collision engine: this layer is
    // added last of the walk overlays, so it would lose every contest with the
    // basemap's own street labels, and a marker that vanishes at some zooms and
    // not others reads as a bug in the count. [MILESTONE_MIN_ZOOM] is what keeps
    // that honest — zoomed out far enough for the badges to crowd, they simply
    // aren't drawn.
    style.addLayer(
        SymbolLayer(LYR_MILESTONES, SRC_MILESTONES).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).apply { minZoom = MILESTONE_MIN_ZOOM },
    )
    style.addLayer(
        CircleLayer(LYR_START, SRC_START).withProperties(
            PropertyFactory.circleColor(accents.anchor.toHexString()),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    // Last, so the pin is never buried under a claim's fill. Overlap is allowed
    // on purpose: there is exactly one home, and a marker the map decides to
    // hide because a street label got there first is a marker the user reads as
    // "my home isn't saved".
    style.addLayer(
        SymbolLayer(LYR_HOME, SRC_HOME).withProperties(
            PropertyFactory.iconImage(IMG_HOME),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    return Overlays(claimed, selected, closeZone, route, path, start, home, milestones)
}

/**
 * Make sure badges 1..[upTo] exist on the style, drawing whatever is missing.
 *
 * Called as the walk grows, so each kilometre costs one small bitmap once —
 * building all [io.app.enclose.geo.DistanceMarkers.MAX_MARKERS] of them up front
 * would be 200 bitmaps on every style load for a walk that will use four.
 */
private fun ensureMilestoneImages(
    style: Style,
    overlays: Overlays,
    upTo: Int,
    accents: EncloseAccents,
    context: android.content.Context,
) {
    val colors = accents.milestone.toArgb() to accents.onMilestone.toArgb()
    // A theme change repaints every badge, not just the new ones: adding an image
    // under an id the style already has replaces it, so the ones already on the
    // map take the new colours rather than being left behind in the old theme.
    val from = if (colors == overlays.milestoneColors) overlays.registeredMilestones else 0
    if (upTo <= from) return
    for (index in from + 1..upTo) {
        style.addImage(
            milestoneImageId(index),
            milestoneMarkerBitmap(
                context = context,
                label = index.toString(),
                fillColor = colors.first,
                textColor = colors.second,
            ),
        )
    }
    overlays.registeredMilestones = upTo
    overlays.milestoneColors = colors
}

@SuppressLint("MissingPermission") // Caller guards on hasLocationPermission.
private fun enableUserLocation(map: MapLibreMap, style: Style, context: android.content.Context) {
    val component = map.locationComponent
    component.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style).build(),
    )
    component.isLocationComponentEnabled = true
    // NONE, even though the map does follow the walker: following is driven from
    // Compose (see MapController.followUser) off the same walk state everything
    // else on screen reads. Handing the camera to the location component instead
    // would put a second animator on it that knows nothing about the walk, and
    // the two would fight over every fix.
    component.cameraMode = CameraMode.NONE
    // NORMAL (plain dot), NOT COMPASS: the compass-bearing animator keeps ticking
    // during map teardown and calls getSourceAs on an invalidated style → crash.
    component.renderMode = RenderMode.NORMAL
}

// Opening view before anything has been saved and before the first GPS fix.
private const val WORLD_LAT = 20.0
private const val WORLD_LNG = 0.0
private const val WORLD_ZOOM = 2.0

/**
 * Centring on a tapped claim. Shorter than a fly-to: the shape is usually
 * already on screen and this is a nudge, not a journey.
 */
private const val CENTER_ANIM_MS = 500

private const val FOCUS_ZOOM = 16.0
private const val FOCUS_ANIM_MS = 1200
private const val FOCUS_POLL_ATTEMPTS = 60
private const val FOCUS_POLL_INTERVAL_MS = 500L

// Mouse-wheel zoom: zoom levels changed per wheel notch, and the animation time.
private const val ZOOM_STEP = 0.6
private const val ZOOM_ANIM_MS = 120

/**
 * How long the follow animation takes. Comfortably under the 3 s fix interval,
 * so each move finishes before the next one starts rather than the camera
 * lurching between two animations it never completes.
 */
private const val FOLLOW_ANIM_MS = 900

// On-screen zoom buttons.
internal const val ZOOM_BUTTON_STEP = 1.0
private const val ZOOM_BUTTON_ANIM_MS = 220

/**
 * Below this zoom the kilometre badges aren't drawn at all. A kilometre is about
 * 30 px on screen here, which is barely wider than a badge — any further out and
 * the marks stop being marks on a trail and become a row of dots covering it.
 */
private const val MILESTONE_MIN_ZOOM = 12f

// Framing a selected territory.
private const val FIT_PADDING_PX = 140
private const val FIT_ANIM_MS = 800

/**
 * The most of the map a caller's inset may claim when framing something. Past
 * half, what's left is too thin to read a loop in and the camera compensates by
 * zooming out until the loop is a dot.
 */
private const val MAX_FIT_INSET_FRACTION = 0.5f

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
        // Via SnapDisplay, which is the one place that decides between the
        // as-walked outline and the road-matched one. Do not reach for
        // `t.polygons` directly here.
        val multi = SnapDisplay.polygonsFor(t)
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

/** One point per kilometre badge, each naming the image that draws it. */
private fun milestonesToFeatures(markers: List<DistanceMarker>): FeatureCollection {
    val features = markers.map { marker ->
        Feature.fromGeometry(point(marker.position)).apply {
            addStringProperty("icon", milestoneImageId(marker.index))
        }
    }
    return FeatureCollection.fromFeatures(features)
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
