package io.app.enclose.wear

import io.app.enclose.watchlink.WatchStatus

/**
 * When the wrist buzzes: once, on the step into the closing radius — the moment
 * stopping would claim. Not on every status after it (a buzz per update is a
 * watch that won't stop vibrating), and not on wandering back out, which the
 * walker finds out by looking.
 *
 * And once when a stretch turns doubtful after the loop closed: whoever has
 * ridden on from a finished loop may believe the walk is lost, and it isn't.
 */
object ReadyAlert {
    fun shouldBuzz(wasReady: Boolean, current: WatchStatus): Boolean =
        isReady(current) && !wasReady

    /** The tap-to-stop prompt stays only while stopping would claim the loop closing here. */
    fun shouldClearPrompt(current: WatchStatus): Boolean = !isReady(current)

    fun isReady(current: WatchStatus): Boolean =
        current.phase == WatchStatus.Phase.WALKING && current.readyToClose

    /**
     * Once per loop: [warnedFor] is the closing time last warned about. A bus
     * slows for every stop, and a buzz each time it pulls away again would be a
     * watch that won't stop vibrating.
     */
    fun shouldWarnDoubt(warnedFor: Long?, current: WatchStatus): Boolean =
        doubtAfterLoop(current) != null && doubtAfterLoop(current) != warnedFor

    /** When the loop closed, while a stretch is in doubt after it; null otherwise. */
    fun doubtAfterLoop(current: WatchStatus): Long? =
        current.loopClosedAtEpochMs?.takeIf { current.phase == WatchStatus.Phase.WALKING && current.doubtful }
}
