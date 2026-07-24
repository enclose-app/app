package io.app.enclose.sync

import android.util.Log
import io.app.enclose.data.Territory

/**
 * The seam between the offline-first local store and your backend.
 *
 * Implement this against your real API (Retrofit/Ktor/etc.) and provide it from
 * [io.app.enclose.EncloseApp]. Everything else — the DB, the worker, the
 * scheduler — stays unchanged.
 */
interface RemoteSyncApi {
    /**
     * Upload the given claimed territories. Return the ids that were accepted
     * by the server; those get marked SYNCED locally. Throw to signal a
     * transient failure (the worker will retry with backoff).
     */
    suspend fun upload(territories: List<Territory>): List<String>
}

/**
 * Placeholder used until a backend exists. It accepts nothing, so territories
 * stay PENDING and the app remains fully functional offline. Swap this out for
 * a real implementation and the rest of the sync pipeline just works.
 */
class NoBackendSyncApi : RemoteSyncApi {
    override suspend fun upload(territories: List<Territory>): List<String> {
        Log.i("EncloseSync", "No backend configured; ${territories.size} territory(ies) kept PENDING.")
        return emptyList()
    }
}
