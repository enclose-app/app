package io.app.enclose

import android.app.Application
import io.app.enclose.data.CityTagger
import io.app.enclose.data.EncloseDatabase
import io.app.enclose.data.ProfileRepository
import io.app.enclose.data.TerritoryRepository
import io.app.enclose.data.UserSettings
import io.app.enclose.data.WalkProgressRepository
import io.app.enclose.data.WalkRepository
import io.app.enclose.geo.CityResolver
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
