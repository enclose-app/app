package io.app.enclose.data

import androidx.room.withTransaction
import io.app.enclose.export.BackupData

/**
 * What a restore actually did, so the user is told rather than left to go and
 * check. Every number here is counted from the database, not from the file.
 */
data class BackupReport(
    val territoriesAdded: Int = 0,
    val territoriesReplaced: Int = 0,
    val walksAdded: Int = 0,
    val walksReplaced: Int = 0,
    val profileRestored: Boolean = false,
    val settingsRestored: Boolean = false,
    /** True when the backup's unfinished walk was adopted — see [BackupRepository]. */
    val walkInProgressRestored: Boolean = false,
    /** True when it was left in the file because this device has one of its own. */
    val walkInProgressSkipped: Boolean = false,
    /** Cached map regions named in the file and deliberately not restored. */
    val offlineRegionsSkipped: Int = 0,
)

/**
 * Reads and writes the whole local dataset for backup and restore.
 *
 * Two decisions shape everything here.
 *
 * **A restore merges; it never deletes.** Rows are written by primary key, so a
 * backup restored onto an empty installation is a complete restore, and restored onto
 * a device that has been walked on since is an addition. The alternative —
 * wiping first, so the device ends up an exact copy of the file — would delete
 * territories walked after the backup was taken, and this app's standing rule is
 * that nothing somebody walked for is destroyed (see `CLAUDE.md`). A user who
 * genuinely wants the file's version of a claim gets it: same id, backup wins.
 *
 * **The cached map regions are backed up but not restored.** `offline_regions`
 * holds MapLibre's id for a downloaded tile pyramid; the tiles themselves live
 * in MapLibre's own store, which is not in the file. Writing those rows onto
 * another device would leave [io.app.enclose.offline.OfflineTileSync] believing
 * those cities are already cached — it skips any city it finds a row for — so
 * they would never download, and the offline map would be silently missing
 * exactly where the user walks most. They stay in the file because a backup is
 * meant to hold everything, and are counted in the report so the omission is
 * stated rather than hidden.
 */
class BackupRepository(
    private val database: EncloseDatabase,
    private val settings: UserSettings,
) {

    /**
     * The Room version this build's database is on.
     *
     * Read from the open database rather than written down next to a copy of the
     * `@Database(version = …)` annotation: a constant here would be one more
     * thing a migration has to remember to bump, and the failure mode is a file
     * that lies about what it holds.
     */
    fun currentSchemaVersion(): Int =
        runCatching { database.openHelper.readableDatabase.version }.getOrDefault(0)

    /** Every row and every preference, as of now. */
    suspend fun collect(appVersionName: String, createdAtEpochMs: Long): BackupData {
        val progressDao = database.walkProgressDao()
        return BackupData(
            createdAtEpochMs = createdAtEpochMs,
            appVersionName = appVersionName,
            schemaVersion = currentSchemaVersion(),
            territories = database.territoryDao().all(),
            walks = database.walkDao().all(),
            profile = database.profileDao().get(),
            walkProgress = progressDao.session(),
            walkProgressPoints = progressDao.points().map { it.toLatLng() },
            offlineRegions = database.offlineRegionDao().all(),
            settings = settings.snapshot(),
        )
    }

    /**
     * Write [data] into the database and preferences, and report what changed.
     *
     * The database half runs in one transaction: a restore interrupted half way
     * would otherwise leave claims whose walks are missing, which reads to the
     * user as data loss caused by the very feature meant to prevent it. The
     * preferences are written afterward and outside it — `SharedPreferences` has
     * no part in a SQLite transaction, and doing them last means a failed restore
     * leaves the device's own settings alone.
     */
    suspend fun restore(data: BackupData): BackupReport {
        val report = database.withTransaction {
            val territoryDao = database.territoryDao()
            val walkDao = database.walkDao()

            val existingTerritories = territoryDao.allIds().toSet()
            val existingWalks = walkDao.allIds().toSet()
            // Counted before writing, because afterward every id exists and the
            // difference between "added" and "replaced" is gone.
            val territoriesReplaced = data.territories.count { it.id in existingTerritories }
            val walksReplaced = data.walks.count { it.id in existingWalks }

            territoryDao.upsertAll(data.territories)
            walkDao.upsertAll(data.walks)
            data.profile?.let { database.profileDao().upsert(it) }

            val progressDao = database.walkProgressDao()
            // The unfinished walk is adopted **only onto a device that has none
            // of its own**. Overwriting a live recording with a stale one from a
            // file is the one thing a restore must never do — those points are
            // the walk somebody is out on right now, and they exist nowhere else.
            val backedUpProgress = data.walkProgress
            val adoptProgress = backedUpProgress != null && progressDao.session() == null
            if (adoptProgress && backedUpProgress != null) {
                progressDao.begin(backedUpProgress)
                progressDao.insertPoints(data.walkProgressPoints.map(WalkProgressPointEntity::of))
            }

            BackupReport(
                territoriesAdded = data.territories.size - territoriesReplaced,
                territoriesReplaced = territoriesReplaced,
                walksAdded = data.walks.size - walksReplaced,
                walksReplaced = walksReplaced,
                profileRestored = data.profile != null,
                walkInProgressRestored = adoptProgress,
                walkInProgressSkipped = backedUpProgress != null && !adoptProgress,
                offlineRegionsSkipped = data.offlineRegions.size,
            )
        }
        settings.restore(data.settings)
        return report.copy(settingsRestored = true)
    }
}
