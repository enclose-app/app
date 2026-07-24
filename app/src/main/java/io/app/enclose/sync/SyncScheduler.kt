package io.app.enclose.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Schedules [SyncWorker] to run when connectivity is available. Call
 * [requestSync] after claiming a territory; WorkManager persists the request
 * and fires it as soon as the device is online (immediately if it already is),
 * so a walk claimed with no signal syncs on its own later.
 */
object SyncScheduler {

    private const val WORK_NAME = "enclose-territory-sync"

    fun requestSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // Keep an already-queued sync rather than restarting its backoff.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
