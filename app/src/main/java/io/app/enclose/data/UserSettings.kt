package io.app.enclose.data

import android.content.Context
import androidx.core.content.edit

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

    private companion object {
        // Unchanged from the inline version — renaming would silently reset
        // choices users have already made.
        const val PREFS_NAME = "enclose_ui"
        const val KEY_SEEN_INTRO = "seen_intro"
        const val KEY_ACTIVITY = "activity_type"
        const val KEY_BASEMAP = "basemap_style"

        const val KEY_TERRITORY_SORT = "territory_sort"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_OFFLINE_STYLE = "offline_style_url"
        const val KEY_OFFLINE_RATIO = "offline_pixel_ratio"
        const val KEY_CAM_LAT = "camera_lat"
        const val KEY_CAM_LNG = "camera_lng"
        const val KEY_CAM_ZOOM = "camera_zoom"
        const val KEY_CAM_BEARING = "camera_bearing"
        const val KEY_CAM_TILT = "camera_tilt"

        const val DEFAULT_ZOOM = 16f
    }
}
