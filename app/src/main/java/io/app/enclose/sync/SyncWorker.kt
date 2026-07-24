package io.app.enclose.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.app.enclose.EncloseApp

/**
 * Pushes any PENDING territories to the backend. Scheduled with a connectivity
 * constraint (see [SyncScheduler]) so it only runs when there's a network, then
 * marks whatever the server accepted as SYNCED. Runs entirely off the local DB,
 * so nothing here is on the app's critical (offline) path.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EncloseApp
        val repository = app.repository
        val api = app.remoteSyncApi

        val pending = repository.pending()
        if (pending.isEmpty()) return Result.success()

        return try {
            val syncedIds = api.upload(pending)
            if (syncedIds.isNotEmpty()) repository.markSynced(syncedIds)
            Result.success()
        } catch (t: Throwable) {
            // Transient failure — let WorkManager retry with backoff.
            Result.retry()
        }
    }
}
