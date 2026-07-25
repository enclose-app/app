package io.app.enclose.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes what the user is physically doing (walking / running / cycling /
 * in a vehicle), so [TrackingManager] can refuse to record a drive.
 *
 * Wraps Play Services activity recognition and normalises its types into
 * [MotionSample]. Everything degrades quietly: if the permission is denied or
 * Play Services isn't present, [latest] simply stays null and the tracker falls
 * back to its speed-only checks.
 */
object ActivityMonitor {

    private val _latest = MutableStateFlow<MotionSample?>(null)

    /** Most recent classification, or null when none has arrived. */
    val latest: StateFlow<MotionSample?> = _latest.asStateFlow()

    private var running = false

    /** True when the user has granted physical-activity access. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Begin classifying. Safe to call repeatedly; a no-op without permission. */
    fun start(context: Context) {
        if (running || !hasPermission(context)) return
        running = runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityUpdates(UPDATE_INTERVAL_MS, pendingIntent(context))
        }.isSuccess
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(pendingIntent(context))
        }
        // Drop the stale reading so a later walk can't be judged on it.
        _latest.value = null
    }

    /** Called by [ActivityUpdateReceiver] for each batch Play Services delivers. */
    internal fun onResult(result: ActivityRecognitionResult) {
        val probable = result.mostProbableActivity
        _latest.value = MotionSample(
            activity = probable.type.toMotionActivity(),
            confidence = probable.confidence,
            // Read the in-vehicle confidence directly: a car waiting at a light
            // reports STILL as most probable while in-vehicle stays high.
            vehicleConfidence = result.getActivityConfidence(DetectedActivity.IN_VEHICLE),
            atElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityUpdateReceiver::class.java)
            .setAction(ACTION_ACTIVITY_UPDATE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // MUTABLE: Play Services fills the results into this intent.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun Int.toMotionActivity(): MotionActivity = when (this) {
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionActivity.WALKING
        DetectedActivity.RUNNING -> MotionActivity.RUNNING
        DetectedActivity.ON_BICYCLE -> MotionActivity.CYCLING
        DetectedActivity.IN_VEHICLE -> MotionActivity.VEHICLE
        DetectedActivity.STILL -> MotionActivity.STILL
        else -> MotionActivity.UNKNOWN
    }

    private const val REQUEST_CODE = 2001
    internal const val ACTION_ACTIVITY_UPDATE = "io.app.enclose.action.ACTIVITY_UPDATE"

    /** Matches the location cadence closely enough to catch a drive quickly. */
    private const val UPDATE_INTERVAL_MS = 5_000L
}

/** Receives activity-recognition batches and hands them to [ActivityMonitor]. */
class ActivityUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        ActivityRecognitionResult.extractResult(intent)?.let(ActivityMonitor::onResult)
    }
}
