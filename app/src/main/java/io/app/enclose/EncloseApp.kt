package io.app.enclose

import android.app.Application
import io.app.enclose.data.EncloseDatabase
import io.app.enclose.data.ProfileRepository
import io.app.enclose.data.TerritoryRepository
import io.app.enclose.data.WalkRepository
import io.app.enclose.sync.NoBackendSyncApi
import io.app.enclose.sync.RemoteSyncApi
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

    /** Swap [NoBackendSyncApi] for your real backend client when ready. */
    val remoteSyncApi: RemoteSyncApi by lazy { NoBackendSyncApi() }

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is created.
        MapLibre.getInstance(this)
    }
}
