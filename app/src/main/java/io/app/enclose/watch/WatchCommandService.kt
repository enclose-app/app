package io.app.enclose.watch

import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.app.enclose.EncloseApp
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Takes Stop, and the steps it counted, from the watch.
 *
 * Usually runs with no screen open — the phone is in a pocket, which is the
 * point of a watch — so Stop does what [io.app.enclose.ui.EncloseViewModel.stopWalk]
 * does without the view model: collect the last steps, finish the walk and stop
 * the GPS. Saving the closed loop is not done here; EncloseApp hears every loop
 * close, from here or anywhere else.
 */
class WatchCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchLink.STOP_PATH -> stop()
            WatchLink.STEPS_PATH -> steps(event.data)
        }
    }

    private fun steps(payload: ByteArray) {
        // A message this build can't read is evidence it can't weigh: dropped.
        val steps = runCatching {
            val map = DataMap.fromByteArray(payload)
            WatchSteps.fromFields { key -> map.get<Any>(key) }
        }.getOrNull() ?: return
        val app = application as EncloseApp
        app.applicationScope.launch(Dispatchers.Main) { WatchStepRequests.accept(steps) }
    }

    private fun stop() {
        // Re-asked of the live walk, not taken from the watch: the watch saw a
        // status a few seconds old, and in those seconds the walk can have
        // ended. Stop at any point of a walk is the watch's call now — it claims
        // the loop that closed or keeps the walk — but only of a walk still on.
        val live = watchStatusOf(TrackingManager.walk.value, TrackingManager.pendingClaim.value)
        if (!live.canStopFromWatch) return

        // On the main thread, where every fix and step count arrives, and on
        // the application's scope, which outlives this service's callback.
        val app = application as EncloseApp
        app.applicationScope.launch(Dispatchers.Main) {
            // Judged on every step, the last few seconds' included — and the
            // watch's last ones too, when a stretch is in doubt.
            WatchStepRequests.gatherLastSteps(app)
            TrackingManager.finishWalk()
            // A walk fed by map taps or a GPX replay never started the service,
            // and starting a service just to stop it is refused from the
            // background. Either way there is nothing to stop, so a refusal is
            // not a failure.
            runCatching { LocationService.stop(app) }
        }
    }
}
