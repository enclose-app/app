package io.app.enclose.watch

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.StepInterval
import io.app.enclose.tracking.StepSource
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchSteps
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's half of the watch's step witness: the steps the watch sends, and
 * the one request Stop makes for the rest.
 *
 * The watch's steps can only help a walk — they turn a doubtful stretch into one
 * on foot, never the reverse — so a watch that never answers costs nothing but
 * the wait, and the wait is capped and only ever spent when a stretch is in
 * doubt.
 *
 * Main thread only, where every fix and step count arrives, so nothing here
 * races [TrackingManager].
 */
object WatchStepRequests {

    private val _asking = MutableStateFlow(false)

    /** True while Stop waits for the watch — what the panel shows instead of its buttons. */
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    /** Stops waiting at once: one from the panel and one from the watch can overlap. */
    private var waiting = 0

    /** Starts of the walks the watch has answered a flush for. */
    private val answers = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Steps from the watch, taken as evidence when they belong to the live walk. */
    fun accept(steps: WatchSteps) {
        val intervals = stepsFromWatch(TrackingManager.walk.value, steps) ?: return
        TrackingManager.onSteps(intervals)
        if (steps.flushed) answers.tryEmit(steps.startedAtEpochMs)
    }

    /**
     * Everything Stop waits for before the walk is judged: the phone's last
     * steps, always, and the watch's when they could change the verdict.
     */
    suspend fun gatherLastSteps(context: Context, askWatch: Boolean = true) {
        LocationService.flushSteps()
        if (!askWatch) return
        val walk = TrackingManager.walk.value
        val startedAt = walk.startedAtMs ?: return
        if (!TrackingManager.hasDoubt(walk)) return
        val nodes = withTimeoutOrNull(NODES_TIMEOUT_MS) {
            runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrNull()
        }.orEmpty()
        if (nodes.isEmpty()) return

        waiting++
        _asking.value = true
        try {
            coroutineScope {
                // Listening before asking, so an answer can't arrive unheard.
                val answer = async(start = CoroutineStart.UNDISPATCHED) { answers.first { it == startedAt } }
                val messages = Wearable.getMessageClient(context)
                val request = WatchSteps.flushRequest(startedAt)
                val asked = nodes.map { node ->
                    runCatching { messages.sendMessage(node.id, WatchLink.STEPS_FLUSH_PATH, request).await() }
                        .isSuccess
                }.any { it }
                if (asked) withTimeoutOrNull(WATCH_TIMEOUT_MS) { answer.await() }
                answer.cancel()
            }
        } finally {
            waiting--
            _asking.value = waiting > 0
        }
    }

    /**
     * Long enough for the watch to ask Health Services for what it is holding
     * and send it back over Bluetooth; short enough that Stop still feels like
     * Stop. A watch that misses it costs only its own evidence.
     */
    private const val WATCH_TIMEOUT_MS = 10_000L

    private const val NODES_TIMEOUT_MS = 2_000L
}

/**
 * The intervals in [steps] as evidence for [walk], or null when they belong to
 * another walk or none is running. Matched on the walk's start, which is what
 * the phone sends the watch in its status.
 */
internal fun stepsFromWatch(walk: TrackingManager.WalkState, steps: WatchSteps): List<StepInterval>? {
    if (!walk.isTracking || walk.startedAtMs != steps.startedAtEpochMs) return null
    return steps.intervals.map { StepInterval(StepSource.WATCH, it.fromEpochMs, it.toEpochMs, it.steps) }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
