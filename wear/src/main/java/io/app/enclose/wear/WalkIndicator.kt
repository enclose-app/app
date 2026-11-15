package io.app.enclose.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.app.enclose.watchlink.WatchStatus

/**
 * The walk as Wear's *ongoing activity*: the icon on the watch face and the
 * chip in Recents and the launcher for as long as the phone is recording one,
 * each a tap back to the walk. Wear's quality guidelines require it of any app
 * with something long-running, and it is the way back for a walker who pressed
 * the crown mid-walk and now wants Stop.
 *
 * It rides an ongoing notification, which is the only thing the system accepts
 * one on. There is no foreground service behind it and there can't honestly be
 * one: the phone records the walk, and nothing runs on the watch between the
 * phone's statuses. So it follows the status — up while it says WALKING, gone
 * when it says anything else — and an IDLE written on every start of the
 * phone's process clears one left by a walk that died with it.
 *
 * Without the notification permission there is no indicator; nothing else
 * about the walk depends on it.
 */
object WalkIndicator {

    fun show(context: Context, status: WatchStatus) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (status.phase != WatchStatus.Phase.WALKING) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.watch_ongoing_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val distance = context.resources.formatDistance(status.distanceMeters)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk_ongoing)
            .setContentTitle(context.getString(R.string.watch_ongoing_title))
            // For a watch that shows the notification itself; where the ongoing
            // activity is understood, its status below is shown instead.
            .setContentText(hintOf(context, status) ?: distance)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Re-posted on every status, about every ten metres: it must never
            // buzz. The buzzes that mean something are ReadyAlert's.
            .setSilent(true)
            .setOnlyAlertOnce(true)

        OngoingActivity.Builder(context, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_walk_ongoing)
            .setTouchIntent(open)
            .setStatus(statusOf(context, status, distance))
            .build()
            .apply(context)

        manager.notify(NOTIFICATION_ID, notification.build())
    }

    /**
     * The chip's line: what needs attention if anything does, otherwise the
     * figures the walking screen leads with — time counted up by the system, so
     * it runs between statuses as the screen's does.
     */
    private fun statusOf(context: Context, status: WatchStatus, distance: String): Status {
        val hint = hintOf(context, status)
        val startedAt = status.startedAtEpochMs
        if (hint != null || startedAt == null) {
            val text = hint ?: context.getString(R.string.watch_hint_finding_gps)
            return Status.Builder().addTemplate("#$PART_TEXT#").addPart(PART_TEXT, Status.TextPart(text)).build()
        }
        // StopwatchPart counts on the elapsed-realtime clock; the walk's start
        // is wall-clock.
        val zero = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startedAt)
        return Status.Builder()
            .addTemplate(context.getString(R.string.watch_walk_figures, "#$PART_TIME#", "#$PART_TEXT#"))
            .addPart(PART_TIME, Status.StopwatchPart(zero))
            .addPart(PART_TEXT, Status.TextPart(distance))
            .build()
    }

    private fun hintOf(context: Context, status: WatchStatus): String? = when {
        status.notRecording -> context.getString(R.string.watch_hint_not_recording)
        status.readyToClose -> context.getString(R.string.watch_hint_ready)
        else -> null
    }

    private const val CHANNEL_ID = "walk_in_progress"

    /** Clear of [StatusListenerService]'s 1 and 2. */
    private const val NOTIFICATION_ID = 3
    private const val PART_TIME = "time"
    private const val PART_TEXT = "text"
}
