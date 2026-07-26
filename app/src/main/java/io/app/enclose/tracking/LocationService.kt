package io.app.enclose.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.app.enclose.EncloseApp
import io.app.enclose.MainActivity
import io.app.enclose.R
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that records the walk. It keeps a high-accuracy location
 * stream alive while the app is backgrounded / the screen is off, and feeds
 * every fix into [TrackingManager]. When the manager reports the loop closed,
 * the service stops itself.
 */
class LocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient

    /** Cancelled in [onDestroy], so nothing outlives the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val progressRepository by lazy {
        (application as EncloseApp).walkProgressRepository
    }
    private val recorder by lazy { WalkProgressRecorder(progressRepository) }

    /** Guards against a second onStartCommand re-running the setup. */
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val now = SystemClock.elapsedRealtime()
            // Every fix in the batch, oldest first — not just `lastLocation`.
            // A dozing or backgrounded device stops delivering and then hands
            // over the whole stretch at once; keeping only the newest threw the
            // walked ground away and left the survivor looking like a teleport.
            for (loc in result.locations) {
                // The fix's own monotonic clock, never the moment it happened to
                // be delivered. A burst arrives milliseconds apart, so timing a
                // several-hundred-metre segment by delivery reads as an
                // impossible speed and used to discard the walk outright.
                val fixAt = loc.elapsedRealtimeNanos / 1_000_000L
                // Fused hands back a cached last-known fix when it reacquires.
                // It describes where the user was, not where they are.
                if (now - fixAt > MAX_FIX_AGE_MS) continue

                // Just record the fix; the loop is closed only when the user stops.
                // Speed and the current activity ride along so the manager can reject
                // movement that isn't human-powered (see MotionGate).
                TrackingManager.onLocation(
                    point = LatLng(loc.latitude, loc.longitude),
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                    speedMps = if (loc.hasSpeed()) loc.speed else null,
                    atElapsedMs = fixAt,
                    motion = ActivityMonitor.latest.value,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        // Classifies walking / running / cycling / in-vehicle. No-op when the
        // physical-activity permission was declined; the speed checks still run.
        ActivityMonitor.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        if (!started) {
            started = true
            scope.launch { beginRecording() }
        }
        return START_STICKY
    }

    /**
     * Start (or resume) recording. Because this service is START_STICKY, the
     * system restarts it after a low-memory kill — and the [TrackingManager] it
     * feeds is process state, so it comes back empty. Without the restore below,
     * every fix after such a restart was silently dropped by the manager's
     * `isTracking` guard while the notification still said "recording": GPS
     * draining, nothing being kept, an hour of walking lost with no error.
     */
    private suspend fun beginRecording() {
        if (!TrackingManager.walk.value.isTracking) {
            val resumed = restoreInterruptedWalk()
            if (!resumed) {
                // Nothing to record. Showing a recording notification and burning
                // battery for fixes nobody will keep is worse than stopping.
                stopSelf()
                return
            }
        }
        if (!requestUpdates()) {
            stopSelf()
            return
        }
        recorder.record(TrackingManager.walk)
    }

    /** True if a walk left behind by a dead process was picked back up. */
    private suspend fun restoreInterruptedWalk(): Boolean {
        val saved = progressRepository.load() ?: return false
        val activityType = runCatching { ActivityType.valueOf(saved.activityTypeName) }
            .getOrDefault(ActivityType.WALK)
        val restored = TrackingManager.restore(
            path = saved.path,
            startedAtMs = saved.startedAtEpochMs,
            activityType = activityType,
            elevationGainMeters = saved.elevationGainMeters,
            movingMs = saved.movingMs,
        )
        if (!restored) {
            progressRepository.clear()
            return false
        }
        // The path and totals are already on disk; don't write them again.
        recorder.adopt(saved.path.size, saved.elevationGainMeters, saved.movingMs)
        return true
    }

    /**
     * Ask for fixes, reporting whether it worked.
     *
     * Permission is checked here rather than trusted from the caller: a
     * START_STICKY restart re-enters this with no UI involved, and the user can
     * revoke location from system settings mid-walk. Either way the request
     * throws, and an uncaught SecurityException would take the service down.
     */
    // Lint can't follow the check into hasLocationPermission(), and the
    // runCatching below covers the race it's really warning about.
    @Suppress("MissingPermission")
    private fun requestUpdates(): Boolean {
        if (!hasLocationPermission()) return false
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        return runCatching {
            fused.requestLocationUpdates(request, callback, mainLooper)
        }.isSuccess
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        // A stopped service with no walk in progress means the walk ended
        // normally — the ViewModel stops the service and finishes the walk in
        // whichever order, and the recorder's own tidy-up may be cancelled by
        // scope.cancel() below before it runs. Clearing on a scope that outlives
        // the service is what stops a finished walk being offered back as an
        // unfinished one. If a walk *is* still in progress, this is a kill we
        // want to survive, so the record stays exactly where it is.
        if (!TrackingManager.walk.value.isTracking) {
            (application as EncloseApp).applicationScope.launch {
                progressRepository.clear()
            }
        }
        scope.cancel()
        fused.removeLocationUpdates(callback)
        ActivityMonitor.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "walk_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "io.app.enclose.action.STOP"
        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /**
         * How old a fix may be and still describe "now". Two minutes is well
         * past the 3 s update interval and past any plausible batching delay,
         * so anything older is a cached position being replayed rather than an
         * observation — and a batch's own fixes are timestamped individually,
         * so dropping the stale ones costs none of the real ones.
         */
        private const val MAX_FIX_AGE_MS = 120_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
