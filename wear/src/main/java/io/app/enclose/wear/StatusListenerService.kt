package io.app.enclose.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.app.enclose.watchlink.WatchLink

/**
 * Buzzes the wrist when the loop becomes ready to close, app open or not.
 *
 * That moment is the reason to wear the companion at all: the walker gets back
 * near where they started and needs to know that stopping *now* claims, without
 * fishing the phone out. The notification that comes with it opens the app on
 * its Stop button.
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
        prefs.edit { putBoolean(KEY_WAS_READY, status.canStopFromWatch) }

        if (ReadyAlert.shouldBuzz(wasReady, status)) {
            buzz()
            promptToStop()
        } else if (ReadyAlert.shouldClearPrompt(status)) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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

    private companion object {
        const val PREFS = "ready_alert"
        const val KEY_WAS_READY = "was_ready"
        const val CHANNEL_ID = "ready_to_close"
        const val NOTIFICATION_ID = 1
    }
}
