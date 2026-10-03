package io.app.enclose.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.WatchLink

/**
 * Takes Stop from the watch.
 *
 * Usually runs with no screen open — the phone is in a pocket, which is the
 * point of a watch — so it does what [io.app.enclose.ui.EncloseViewModel.stopWalk]
 * does without the view model: stop the GPS and finish the walk. Saving the
 * closed loop is not done here; EncloseApp hears every loop close, from here or
 * anywhere else.
 */
class WatchCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchLink.STOP_PATH) return

        // Re-asked of the live walk, not taken from the watch: the watch saw a
        // status a few seconds old, and in those seconds the walker can have
        // stepped back out of the closing radius. Stop on a walk that isn't
        // ready throws it away, and that is never the watch's call.
        val live = watchStatusOf(TrackingManager.walk.value, TrackingManager.pendingClaim.value)
        if (!live.canStopFromWatch) return

        // A walk fed by map taps or a GPX replay never started the service, and
        // starting a service just to stop it is refused from the background.
        // Either way there is nothing to stop, so a refusal is not a failure.
        runCatching { LocationService.stop(this) }
        TrackingManager.finishWalk()
    }
}
