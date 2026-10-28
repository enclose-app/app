package io.app.enclose.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's hardware step counter, read for as long as a walk is recorded.
 *
 * Steps are the evidence that lets a stretch faster than walking pace count (see
 * [StretchJudge]), so this runs beside the location stream in [LocationService]
 * and hands each interval to [TrackingManager.onSteps]. All the deciding is in
 * [StepCounterIntervals]; this only wires it to the sensor.
 *
 * It costs next to nothing: the counter runs in the sensor hub whether anyone
 * listens or not, and readings are batched there ([MAX_REPORT_LATENCY_US]) and
 * collected by [flush] on a heartbeat rather than waking the phone per step.
 *
 * With no sensor, or without the physical-activity permission, [start] returns
 * false and the walk is judged without phone steps — no evidence, never zero.
 *
 * All callbacks arrive on the main thread, the same thread as every fix, so
 * nothing here races [TrackingManager].
 */
internal class PhoneStepCounter(
    context: Context,
    /** Wall clock minus the monotonic one, shared with the fixes so both are on one clock. */
    private val epochBaseMs: Long,
    private val onInterval: (StepInterval) -> Unit,
) : SensorEventListener2 {

    // No context is kept: LocationService holds the running counter in a static
    // field so Stop can flush it, and a service held there would outlive itself.
    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
    private val counter = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** Read as the walk starts, the one time [start] is called. */
    private val permitted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    private val intervals = StepCounterIntervals(StepSource.PHONE)
    private val waiting = mutableListOf<CompletableDeferred<Unit>>()
    private var running = false

    /** Begin counting. False when the phone can't: no sensor, or no permission. */
    fun start(): Boolean {
        if (running) return true
        val manager = sensors ?: return false
        val sensor = counter ?: return false
        if (!permitted) return false
        running = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US)
        }.getOrDefault(false)
        return running
    }

    fun stop() {
        if (running) runCatching { sensors?.unregisterListener(this) }
        running = false
        release()
    }

    /** Ask the sensor for what it is holding; the interval is reported once it lands. */
    fun flush() {
        if (!running) return
        // A sensor that can't flush still delivers as it goes, so report now.
        if (runCatching { sensors?.flush(this) }.getOrNull() != true) report()
    }

    /**
     * [flush] and wait for it, up to [timeoutMs] — so Stop judges the last
     * stretch on its steps. Gives up quietly: a late count costs that stretch
     * its evidence, and Stop must never hang on a sensor.
     */
    suspend fun flushAndWait(timeoutMs: Long) {
        if (!running) return
        val done = CompletableDeferred<Unit>()
        waiting += done
        flush()
        withTimeoutOrNull(timeoutMs) { done.await() }
        waiting.remove(done)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values.firstOrNull() ?: return
        intervals.onReading(total.toLong(), now())
    }

    override fun onFlushCompleted(sensor: Sensor) = report()

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun report() {
        intervals.report(now())?.let(onInterval)
        release()
    }

    private fun release() {
        waiting.forEach { it.complete(Unit) }
        waiting.clear()
    }

    private fun now(): Long = epochBaseMs + SystemClock.elapsedRealtime()

    companion object {
        /** Readings may wait in the sensor hub this long; the heartbeat collects them sooner. */
        private const val MAX_REPORT_LATENCY_US = 20_000_000
    }
}
