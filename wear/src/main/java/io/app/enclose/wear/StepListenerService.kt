package io.app.enclose.wear

import android.os.SystemClock
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import io.app.enclose.watchlink.WatchSteps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Where Health Services delivers the steps it counted while a walk is on.
 * Registered and cleared by [StepRecorder], which keeps and sends them.
 */
class StepListenerService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // Timed from boot; the phone judges on the wall clock.
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val batch = dataPoints.getData(DataType.STEPS).map {
            WatchSteps.Interval(
                fromEpochMs = it.getStartInstant(boot).toEpochMilli(),
                toEpochMs = it.getEndInstant(boot).toEpochMilli(),
                steps = it.value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        // A binder thread, which may wait a moment; capped, so a phone out of
        // reach can't hold it.
        runBlocking {
            withTimeoutOrNull(DELIVERY_TIMEOUT_MS) { StepRecorder(this@StepListenerService).onDelivered(batch) }
        }
    }

    override fun onPermissionLost() {
        StepRecorder(this).onPermissionLost()
    }

    private companion object {
        const val DELIVERY_TIMEOUT_MS = 5_000L
    }
}
