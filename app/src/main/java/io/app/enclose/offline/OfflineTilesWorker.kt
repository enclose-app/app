package io.app.enclose.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.app.enclose.EncloseApp
import java.time.Duration

/**
 * Downloads map tiles for the cities the user has claims in.
 *
 * A worker rather than a coroutine on the claim path: this is tens of megabytes,
 * it must wait for Wi-Fi, and it should survive the app being closed — which is
 * WorkManager's job description exactly.
 */
class OfflineTilesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EncloseApp
        val styleUrl = inputData.getString(KEY_STYLE_URL) ?: return Result.success()
        val pixelRatio = inputData.getFloat(KEY_PIXEL_RATIO, 1f)

        return try {
            app.offlineTileSync.sync(styleUrl, pixelRatio)
            Result.success()
        } catch (t: Throwable) {
            // Nothing is broken by failing: the map streams as it always has.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "enclose-offline-tiles"
        const val KEY_STYLE_URL = "styleUrl"
        const val KEY_PIXEL_RATIO = "pixelRatio"
    }
}

object OfflineTilesScheduler {

    /**
     * Ask for a cache refresh. Unmetered-only and requiring storage headroom,
     * so this can never eat someone's data plan or fill their phone; if neither
     * is ever true the download simply doesn't happen, which costs the user
     * nothing they had before.
     */
    fun request(context: Context, styleUrl: String, pixelRatio: Float) {
        val request = OneTimeWorkRequestBuilder<OfflineTilesWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(OfflineTilesWorker.KEY_STYLE_URL, styleUrl)
                    .putFloat(OfflineTilesWorker.KEY_PIXEL_RATIO, pixelRatio)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // Keep a queued refresh rather than restarting it: claiming three
            // loops in a row shouldn't queue three downloads of the same city.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val WORK_NAME = "enclose-offline-tiles"
}
