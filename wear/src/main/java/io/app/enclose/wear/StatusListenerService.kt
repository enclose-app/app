package io.app.enclose.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.text.format.DateFormat
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchSteps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * Buzzes the wrist when the loop becomes ready to close, app open or not, and
 * counts the walk's steps while the phone records one.
 *
 * That moment is the reason to wear the companion at all: the walker gets back
 * near where they started and needs to know that stopping *now* claims, without
 * fishing the phone out. The notification that comes with it opens the app on
 * its Stop button.
 *
 * The steps follow the same status — counting starts when a walk does and stops
 * when it ends ([StepRecorder]) — and the phone's Stop can ask for the last of
 * them ([WatchLink.STEPS_FLUSH_PATH]).
 *
 * Play Services calls in on a background thread of its own, so the short waits
 * below block nothing the user sees.
 */
class StatusListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val status = events
            .lastOrNull { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchLink.STATUS_PATH }
            ?.let { statusOf(it.dataItem) }
            ?: return

        // Remembered across service instances, which live only as long as one
        // delivery: without it every update would look like the first.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasReady = prefs.getBoolean(KEY_WAS_READY, false)
        val warnedFor = if (prefs.contains(KEY_WARNED_FOR)) prefs.getLong(KEY_WARNED_FOR, 0L) else null
        val loopClosedAt = ReadyAlert.doubtAfterLoop(status)
        prefs.edit {
            putBoolean(KEY_WAS_READY, ReadyAlert.isReady(status))
            if (loopClosedAt != null) putLong(KEY_WARNED_FOR, loopClosedAt)
        }

        if (ReadyAlert.shouldBuzz(wasReady, status)) {
            buzz()
            promptToStop()
        } else if (ReadyAlert.shouldClearPrompt(status)) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        if (ReadyAlert.shouldWarnDoubt(warnedFor, status)) {
            buzz()
            warnDoubt(loopClosedAt!!)
        } else if (loopClosedAt == null) {
            getSystemService(NotificationManager::class.java).cancel(DOUBT_NOTIFICATION_ID)
        }

        // After the buzz, which can't wait on Health Services.
        runBlocking { withTimeoutOrNull(FOLLOW_TIMEOUT_MS) { StepRecorder(this@StatusListenerService).follow(status) } }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchLink.STEPS_FLUSH_PATH) return
        // The phone holds Stop until this answers, for 10 s at most; past that
        // the answer would be heard by nobody.
        runBlocking {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                StepRecorder(this@StatusListenerService).flush(WatchSteps.flushRequestOf(event.data))
            }
        }
    }

    private fun buzz() {
        // Two firm pulses: different enough from a message arriving to be read
        // without looking.
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1),
        )
    }

    private fun promptToStop() {
        // Declined is fine — the buzz above already said it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.watch_ready_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.watch_ready_title))
                .setContentText(getString(R.string.watch_ready_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                // The buzz is ours; the system's would be a second one.
                .setSilent(true)
                .build(),
        )
    }

    /** "Looks like a vehicle — your loop closed at 14:32": it is still the walker's. */
    private fun warnDoubt(loopClosedAt: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DOUBT_CHANNEL_ID,
                getString(R.string.watch_doubt_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            DOUBT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, DOUBT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.watch_doubt_title))
                .setContentText(
                    getString(R.string.watch_doubt_body, DateFormat.getTimeFormat(this).format(Date(loopClosedAt))),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .build(),
        )
    }

    private companion object {
        const val PREFS = "ready_alert"
        const val KEY_WAS_READY = "was_ready"
        const val KEY_WARNED_FOR = "doubt_warned_for"
        const val CHANNEL_ID = "ready_to_close"
        const val NOTIFICATION_ID = 1
        const val DOUBT_CHANNEL_ID = "doubt_after_loop"
        const val DOUBT_NOTIFICATION_ID = 2
        const val FOLLOW_TIMEOUT_MS = 5_000L
        const val FLUSH_TIMEOUT_MS = 8_000L
    }
}
