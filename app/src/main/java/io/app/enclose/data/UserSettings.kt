package io.app.enclose.data

import android.content.Context
import androidx.core.content.edit
import io.app.enclose.geo.LatLng

/** Where the map was left: centre, zoom, and orientation. */
data class MapCamera(
    val lat: Double,
    val lng: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
)

/**
 * Every preference the app remembers between launches, in one place.
 *
 * These were previously scattered as ad-hoc `SharedPreferences` reads, which is
 * how the map camera and a couple of toggles ended up being the only things that
 * *weren't* remembered — there was nowhere obvious to notice the omission. A new
 * setting belongs here, so "is this persisted?" has a single answer.
 *
 * Enum-valued settings are stored and returned as raw names: this layer has no
 * business importing UI or tracking types, and an unrecognised name (a rename,
 * a downgrade) resolves to the caller's default rather than crashing.
 *
 * The file and key names are deliberately unchanged from when they were inline,
 * so nothing a user has already chosen is lost.
 */
class UserSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the "how it works" explainer has been shown at least once. */
    var seenIntro: Boolean
        get() = prefs.getBoolean(KEY_SEEN_INTRO, false)
        set(value) = prefs.edit { putBoolean(KEY_SEEN_INTRO, value) }

    /** Declared [io.app.enclose.tracking.ActivityType] name, or null if unset. */
    var activityTypeName: String?
        get() = prefs.getString(KEY_ACTIVITY, null)
        set(value) = prefs.edit { putString(KEY_ACTIVITY, value) }

    /** Chosen basemap (`SYSTEM`/`LIGHT`/`DARK`) name, or null if unset. */
    var basemapStyleName: String?
        get() = prefs.getString(KEY_BASEMAP, null)
        set(value) = prefs.edit { putString(KEY_BASEMAP, value) }

    /** Sort order of the territory list, or null if never changed. */
    var territorySortName: String?
        get() = prefs.getString(KEY_TERRITORY_SORT, null)
        set(value) = prefs.edit { putString(KEY_TERRITORY_SORT, value) }

    /**
     * Tap-to-place test mode. Remembered because it's sticky in practice —
     * anyone using it is mid-session on it, and silently dropping back to real
     * GPS tracking between launches is the surprising behaviour.
     */
    var testMode: Boolean
        get() = prefs.getBoolean(KEY_TEST_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_TEST_MODE, value) }

    /**
     * Whether claimed routes may be matched onto real roads and paths.
     *
     * **Off by default, and that default is not a formality.** Everything else
     * this app does happens on the device: the basemap streams tiles, the
     * geocoder runs locally, and the sync seam accepts nothing. Matching is the
     * first feature that would send somewhere a precise record of where somebody
     * walked, so it has to be asked for rather than discovered.
     *
     * Turning it on covers *new* claims only. Existing ones are matched solely by
     * an explicit action that says how many walks it would upload first — a
     * single toggle must never ship a walking history.
     */
    var snapToPaths: Boolean
        get() = prefs.getBoolean(KEY_SNAP_TO_PATHS, false)
        set(value) = prefs.edit { putBoolean(KEY_SNAP_TO_PATHS, value) }

    /**
     * Whether the bottom control panel is minimised to a single row.
     *
     * Remembered because it's a standing preference about the map, not a
     * per-walk one: someone who minimised it to see more ground wants it
     * minimised on the next walk too, and re-collapsing it every time is exactly
     * the kind of small friction that makes the map feel like it's fighting back.
     */
    var panelCollapsed: Boolean
        get() = prefs.getBoolean(KEY_PANEL_COLLAPSED, false)
        set(value) = prefs.edit { putBoolean(KEY_PANEL_COLLAPSED, value) }

    /**
     * Whether the floating (picture-in-picture) window is allowed.
     *
     * Off by default and never turned on by the app: a window that appears over
     * whatever the user was doing has to be something they asked for. When on,
     * leaving the app mid-walk floats the live stats instead of hiding them.
     */
    var floatingWindow: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_WINDOW, false)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_WINDOW, value) }

    /**
     * The basemap style and screen density the offline downloader should use.
     * Recorded by the map, because a background worker has neither a map nor a
     * window to ask.
     */
    var offlineStyleUrl: String?
        get() = prefs.getString(KEY_OFFLINE_STYLE, null)
        set(value) = prefs.edit { putString(KEY_OFFLINE_STYLE, value) }

    var offlinePixelRatio: Float
        get() = prefs.getFloat(KEY_OFFLINE_RATIO, 1f)
        set(value) = prefs.edit { putFloat(KEY_OFFLINE_RATIO, value) }

    /**
     * Where the map was last left, or null before the first pan.
     *
     * Zoom is the point of this: reopening the app on a world view after the
     * user had carefully framed their neighbourhood loses work they did with
     * their fingers. Stored as separate floats rather than a blob so a partial
     * or corrupt write can't produce a camera that fails to parse — a missing
     * centre simply reads as "no saved camera".
     */
    var camera: MapCamera?
        get() {
            if (!prefs.contains(KEY_CAM_LAT) || !prefs.contains(KEY_CAM_LNG)) return null
            val lat = prefs.getFloat(KEY_CAM_LAT, 0f).toDouble()
            val lng = prefs.getFloat(KEY_CAM_LNG, 0f).toDouble()
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return MapCamera(
                lat = lat,
                lng = lng,
                zoom = prefs.getFloat(KEY_CAM_ZOOM, DEFAULT_ZOOM).toDouble(),
                bearing = prefs.getFloat(KEY_CAM_BEARING, 0f).toDouble(),
                tilt = prefs.getFloat(KEY_CAM_TILT, 0f).toDouble(),
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit {
                    remove(KEY_CAM_LAT)
                    remove(KEY_CAM_LNG)
                    remove(KEY_CAM_ZOOM)
                    remove(KEY_CAM_BEARING)
                    remove(KEY_CAM_TILT)
                }
                return
            }
            prefs.edit {
                putFloat(KEY_CAM_LAT, value.lat.toFloat())
                putFloat(KEY_CAM_LNG, value.lng.toFloat())
                putFloat(KEY_CAM_ZOOM, value.zoom.toFloat())
                putFloat(KEY_CAM_BEARING, value.bearing.toFloat())
                putFloat(KEY_CAM_TILT, value.tilt.toFloat())
            }
        }

    /**
     * The position the map's home button returns to, or null until the user
     * sets one. Cleared by resetting it, never by anything automatic.
     *
     * Stored as raw double bits rather than the floats the camera uses: a
     * camera is a framing and survives a metre of rounding, but home is a
     * doorstep the user pointed at once, and rounding it on every read/write
     * cycle is drift with no upside. Both keys must be present for the value to
     * read back, so a partial write reads as "no home" rather than as a point
     * in the Gulf of Guinea.
     */
    var home: LatLng?
        get() {
            if (!prefs.contains(KEY_HOME_LAT) || !prefs.contains(KEY_HOME_LNG)) return null
            val lat = Double.fromBits(prefs.getLong(KEY_HOME_LAT, 0L))
            val lng = Double.fromBits(prefs.getLong(KEY_HOME_LNG, 0L))
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return LatLng(lat, lng)
        }
        set(value) {
            if (value == null) {
                prefs.edit {
                    remove(KEY_HOME_LAT)
                    remove(KEY_HOME_LNG)
                }
                return
            }
            prefs.edit {
                putLong(KEY_HOME_LAT, value.lat.toRawBits())
                putLong(KEY_HOME_LNG, value.lng.toRawBits())
            }
        }

    private companion object {
        // Unchanged from the inline version — renaming would silently reset
        // choices users have already made.
        const val PREFS_NAME = "enclose_ui"
        const val KEY_SEEN_INTRO = "seen_intro"
        const val KEY_ACTIVITY = "activity_type"
        const val KEY_BASEMAP = "basemap_style"

        const val KEY_TERRITORY_SORT = "territory_sort"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_SNAP_TO_PATHS = "snap_to_paths"
        const val KEY_PANEL_COLLAPSED = "panel_collapsed"
        const val KEY_FLOATING_WINDOW = "floating_window"
        const val KEY_OFFLINE_STYLE = "offline_style_url"
        const val KEY_OFFLINE_RATIO = "offline_pixel_ratio"
        const val KEY_CAM_LAT = "camera_lat"
        const val KEY_CAM_LNG = "camera_lng"
        const val KEY_CAM_ZOOM = "camera_zoom"
        const val KEY_CAM_BEARING = "camera_bearing"
        const val KEY_CAM_TILT = "camera_tilt"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"

        const val DEFAULT_ZOOM = 16f
    }
}
