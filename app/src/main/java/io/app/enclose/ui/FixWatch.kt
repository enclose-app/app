package io.app.enclose.ui

import io.app.enclose.tracking.TrackingManager

/** What the panel should say when a walk has been running but recording nothing. */
internal enum class FixWarning {
    /** No fix has arrived at all: no signal, or nothing is subscribed to it. */
    NO_FIX,

    /**
     * Fixes are arriving but every one is too vague to keep — see
     * [TrackingManager.MAX_ACCURACY_METERS]. Approximate-only location, or a
     * genuinely bad sky view.
     */
    TOO_VAGUE,
}

/**
 * Decides when "acquiring…" has stopped being a plausible thing to say.
 *
 * A walk whose first fix never lands looks exactly like a walk that is about to
 * start — the GPS read-out says `acquiring…`, the figures sit at zero, and the
 * panel says "Walking". That state was indistinguishable from a normal warm-up
 * for as long as the user was willing to keep walking, and the only way out of
 * it was to stop and discard.
 *
 * The two cases are told apart because they need different things from the user:
 * [FixWarning.NO_FIX] means nothing is arriving, [FixWarning.TOO_VAGUE] means
 * plenty is arriving and all of it is being thrown away. Telling someone to go
 * outside when the real problem is approximate location wastes their walk.
 *
 * Pure so it can be tested: none of this can be reached from a unit test once it
 * is inside a composable.
 */
internal object FixWatch {

    /**
     * How long a walk may record nothing before the panel stops calling it
     * warm-up.
     *
     * A cold GPS start is commonly 15–30 s, and fixes are requested every 3 s, so
     * thirty seconds is past the point where silence is still ordinary — while
     * being short enough that nobody walks half a loop before finding out.
     */
    const val WARN_AFTER_MS = 30_000L

    /**
     * @param isTracking a walk is in progress.
     * @param recordedPoints points on the path so far. Any at all means recording
     *   demonstrably works, whatever the current fix looks like.
     * @param accuracyMeters accuracy of the most recent fix, null if none has
     *   ever arrived.
     * @param waitingMs how long the walk has been running.
     */
    fun warning(
        isTracking: Boolean,
        recordedPoints: Int,
        accuracyMeters: Float?,
        waitingMs: Long,
    ): FixWarning? = when {
        !isTracking -> null
        // Recording works. Losing the signal later is the signal-gap machinery's
        // problem, and it is deliberately tolerant about it.
        recordedPoints > 0 -> null
        waitingMs < WARN_AFTER_MS -> null
        accuracyMeters == null -> FixWarning.NO_FIX
        accuracyMeters > TrackingManager.MAX_ACCURACY_METERS -> FixWarning.TOO_VAGUE
        // A fix good enough to keep has arrived; the point it anchors is due on
        // this same update. Warning here would flicker for one frame.
        else -> null
    }
}
