package io.app.enclose

import android.app.Application
import io.app.enclose.data.BackupRepository
import io.app.enclose.data.CityTagger
import io.app.enclose.data.EncloseDatabase
import io.app.enclose.data.ProfileRepository
import io.app.enclose.data.SnapTagger
import io.app.enclose.data.TerritoryRepository
import io.app.enclose.data.UserSettings
import io.app.enclose.data.VoidedWalk
import io.app.enclose.data.VoidedWalkRepository
import io.app.enclose.data.WalkProgressRepository
import io.app.enclose.data.WalkRepository
import io.app.enclose.geo.CityResolver
import io.app.enclose.geo.NoRouteMatcher
import io.app.enclose.geo.RouteMatcher
import io.app.enclose.offline.OfflineTileCache
import io.app.enclose.offline.OfflineTileSync
import io.app.enclose.sync.NoBackendSyncApi
import io.app.enclose.sync.RemoteSyncApi
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.toWalk
import io.app.enclose.watch.WatchPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/**
 * App-wide singletons. A tiny hand-rolled service locator is enough here — the
 * database, repository, and sync API are created once and shared by the UI and
 * the background [io.app.enclose.sync.SyncWorker].
 */
class EncloseApp : Application() {

    val database by lazy { EncloseDatabase.get(this) }
    val repository by lazy { TerritoryRepository(database.territoryDao()) }

    /** Every successful closed-loop walk, persisted locally (offline-first). */
    val walkRepository by lazy { WalkRepository(database.walkDao()) }

    /** Walks the anti-cheat ended, kept as records — never claims. */
    val voidedWalkRepository by lazy { VoidedWalkRepository(database.voidedWalkDao()) }

    /** Local, offline-first user profile (random guest name until sign-in). */
    val profileRepository by lazy { ProfileRepository(database.profileDao()) }

    /**
     * The walk being recorded right now, mirrored to disk so a low-memory kill
     * mid-walk doesn't erase it. Read by [io.app.enclose.tracking.LocationService].
     */
    val walkProgressRepository by lazy { WalkProgressRepository(database.walkProgressDao()) }

    /**
     * Names the city each claim sits in. Shared so the map and profile screens
     * can't run competing backfills.
     */
    val cityTagger by lazy { CityTagger(repository, cityResolver) }

    /**
     * Shared so its lookup cache is shared too: the territory detail screen and
     * the tagger ask about the same coordinates, and the Geocoder is a network
     * call worth making once.
     */
    val cityResolver by lazy { CityResolver(this) }

    /** Everything the app remembers between launches. */
    val settings by lazy { UserSettings(this) }

    /**
     * Reads and writes the whole dataset for backup/restore. Takes the database
     * rather than the repositories: a backup is every table, and going through
     * the domain layer would quietly drop whatever the domain models don't carry.
     */
    val backupRepository by lazy { BackupRepository(database, settings) }

    /**
     * Matches claimed routes onto real roads and paths.
     *
     * [NoRouteMatcher] is bound because no host has been chosen — see
     * [RouteMatcher] for why that is a decision and not an omission. Swap this
     * one line for a real client and the rest of the feature is already built,
     * tested and gated behind the user's opt-in.
     */
    val routeMatcher: RouteMatcher by lazy { NoRouteMatcher() }

    /**
     * Fills in the road-matched outline for claims. Shared for the same reason
     * [cityTagger] is: two screens must not be able to run competing backfills
     * against a rate-limited service.
     */
    val snapTagger by lazy {
        SnapTagger(
            repository = repository,
            matcher = routeMatcher,
            // Read per call, never captured: the user can turn this off between
            // one claim and the next, and the answer that matters is the one at
            // the moment something would be uploaded.
            enabled = { settings.snapToPaths },
        )
    }

    /**
     * Keeps map tiles for claimed cities on the device, so walking out of
     * signal doesn't leave a gray screen. Shared so the worker and the map
     * agree on which regions exist.
     */
    val offlineTileSync by lazy {
        OfflineTileSync(
            territories = repository,
            dao = database.offlineRegionDao(),
            cache = OfflineTileCache(this),
        )
    }

    /** Swap [NoBackendSyncApi] for your real backend client when ready. */
    val remoteSyncApi: RemoteSyncApi by lazy { NoBackendSyncApi() }

    /**
     * For work that must finish even though the component that asked for it is
     * going away — clearing the finished walk as the location service is torn
     * down, for instance, which its own scope would cancel halfway.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is created.
        MapLibre.getInstance(this)

        // These two react to the walk ending, and a walk can end with no screen
        // open: Stop pressed on the watch, or a void while the phone is in a
        // pocket. They used to live in EncloseViewModel, where a loop closed with
        // the map screen gone was never saved and a voided walk left the GPS on.
        // The process is alive whenever a walk is (LocationService is a
        // foreground service), so the application is the scope that always hears.

        // Persist EVERY successful closed loop the moment it closes — offline,
        // in local SQLite — whether or not the user goes on to claim it.
        applicationScope.launch {
            TrackingManager.pendingClaim.collect { pending ->
                if (pending != null) walkRepository.saveClosed(pending.toWalk(claimed = false))
            }
        }
        // A walk voided for vehicle movement: shut the GPS service down (the
        // manager can't, by design). Explaining why is the UI's job, and the
        // view model still does it.
        applicationScope.launch {
            TrackingManager.voidEvents.collect { LocationService.stop(this@EncloseApp) }
        }
        // ...and keep what a voided walk had recorded. The void stands — nothing
        // is claimed — but the ground covered is no longer erased with it.
        applicationScope.launch {
            TrackingManager.voidedRecordings.collect { r ->
                voidedWalkRepository.save(
                    VoidedWalk(
                        id = r.id,
                        path = r.path,
                        startedAtEpochMs = r.startedAtEpochMs,
                        voidedAtEpochMs = r.voidedAtEpochMs,
                        distanceMeters = r.distanceMeters,
                        reason = VoidedWalk.Reason.of(r.reason.name),
                    ),
                )
            }
        }

        // The Galaxy Watch companion's view of the walk.
        WatchPublisher.start(this, applicationScope, repository.territories)
    }
}
