package io.app.enclose.data

import android.content.Context
import androidx.core.content.edit
import io.app.enclose.BuildConfig

/**
 * Demo mode: the app runs on its own database, `enclose-demo.db`, seeded with
 * [DemoData] — for screenshots and presentations. Debug builds only.
 *
 * A separate file rather than demo rows beside real ones, because nothing the
 * user walked for may be touched (see CLAUDE.md): the real database isn't
 * opened at all while this is on, and comes back exactly as it was when it is
 * turned off. Switching needs a fresh process, since the database is opened
 * once per process — [io.app.enclose.ui.EncloseViewModel.setDemoMode] restarts.
 *
 * Kept out of [UserSettings] on purpose: that file is backed up, and a backup
 * restored on another device must not switch it into a demo.
 */
object DemoMode {

    fun isOn(context: Context): Boolean = BuildConfig.DEBUG && prefs(context).getBoolean(KEY_ON, false)

    /**
     * Turn demo mode on or off for the next process. The map camera is swapped
     * too, so the demo opens over its claims and the real map comes back framed
     * where it was left.
     */
    fun set(context: Context, on: Boolean, settings: UserSettings) {
        if (on && !BuildConfig.DEBUG) return
        if (on == isOn(context)) return
        val prefs = prefs(context)
        if (on) {
            settings.camera?.let { real ->
                prefs.edit {
                    putFloat(KEY_REAL_LAT, real.lat.toFloat())
                    putFloat(KEY_REAL_LNG, real.lng.toFloat())
                    putFloat(KEY_REAL_ZOOM, real.zoom.toFloat())
                }
            }
            settings.camera = DemoData.camera
        } else {
            settings.camera = if (prefs.contains(KEY_REAL_LAT)) {
                MapCamera(
                    lat = prefs.getFloat(KEY_REAL_LAT, 0f).toDouble(),
                    lng = prefs.getFloat(KEY_REAL_LNG, 0f).toDouble(),
                    zoom = prefs.getFloat(KEY_REAL_ZOOM, 14f).toDouble(),
                )
            } else {
                null
            }
            prefs.edit {
                remove(KEY_REAL_LAT)
                remove(KEY_REAL_LNG)
                remove(KEY_REAL_ZOOM)
            }
        }
        // commit, not apply: the process is killed right after this.
        prefs.edit(commit = true) { putBoolean(KEY_ON, on) }
    }

    /** The database file to open. */
    fun databaseName(context: Context): String = if (isOn(context)) "enclose-demo.db" else "enclose.db"

    /** Fill an empty demo database. Does nothing to one that has claims already. */
    suspend fun seedIfEmpty(database: EncloseDatabase, nowMs: Long) {
        if (database.territoryDao().allIds().isNotEmpty()) return
        val set = DemoData.build(nowMs)
        TerritoryRepository(database.territoryDao()).let { repo -> set.territories.forEach { repo.claim(it) } }
        WalkRepository(database.walkDao()).let { repo -> set.walks.forEach { repo.saveClaimed(it) } }
        VoidedWalkRepository(database.voidedWalkDao()).let { repo -> set.voided.forEach { repo.save(it) } }
        database.profileDao().upsert(
            ProfileEntity(
                firstName = DemoData.FIRST_NAME,
                lastName = DemoData.LAST_NAME,
                createdAtEpochMs = nowMs - DemoData.MEMBER_DAYS * 24 * 3_600_000L,
            ),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("enclose_demo", Context.MODE_PRIVATE)

    private const val KEY_ON = "on"
    private const val KEY_REAL_LAT = "real_camera_lat"
    private const val KEY_REAL_LNG = "real_camera_lng"
    private const val KEY_REAL_ZOOM = "real_camera_zoom"
}
