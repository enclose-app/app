package io.app.enclose

import android.app.Application
import io.app.enclose.data.CityTagger
import io.app.enclose.data.EncloseDatabase
import io.app.enclose.data.ProfileRepository
import io.app.enclose.data.RouteSuggester
import io.app.enclose.data.SnapTagger
import io.app.enclose.data.TerritoryRepository
import io.app.enclose.data.UserSettings
import io.app.enclose.data.WalkProgressRepository
import io.app.enclose.data.WalkRepository
import io.app.enclose.geo.CityResolver
import io.app.enclose.geo.NoRouteMatcher
import io.app.enclose.geo.OpenFreeMapWalkableArea
import io.app.enclose.geo.RouteMatcher
import io.app.enclose.offline.OfflineTileCache
import io.app.enclose.offline.OfflineTileSync
import io.app.enclose.sync.NoBackendSyncApi
import io.app.enclose.sync.RemoteSyncApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * the tagger ask about the same coordinates, and the geocoder is a network
     * call worth making once.
     */
    val cityResolver by lazy { CityResolver(this) }

    /** Everything the app remembers between launches. */
    val settings by lazy { UserSettings(this) }

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
     * Suggests a loop of the length the user asks for, starting from where they
     * are standing.
     *
     * Shared for the same reason [cityResolver] is: the tile cache behind it is
     * what makes pressing "another one" free, and a second instance would be a
     * second empty cache re-downloading the same square kilometre.
     *
     * Note what is *not* here — no key, no new host, no new terms. The roads
     * come out of the same OpenFreeMap vector tiles the basemap already draws
     * (see [io.app.enclose.geo.OpenFreeMapWalkableArea]), which is why this
     * feature could be built at all where [routeMatcher] is still unbound.
     */
    val routeSuggester by lazy { RouteSuggester(OpenFreeMapWalkableArea()) }

    /**
     * Keeps map tiles for claimed cities on the device, so walking out of
     * signal doesn't leave a grey screen. Shared so the worker and the map
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
    }
}
