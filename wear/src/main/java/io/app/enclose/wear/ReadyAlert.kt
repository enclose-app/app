package io.app.enclose.wear

import io.app.enclose.watchlink.WatchStatus

/**
 * When the wrist buzzes: once, on the step into the closing radius — the moment
 * stopping would claim. Not on every status after it (a buzz per update is a
 * watch that won't stop vibrating), and not on wandering back out, which the
 * walker finds out by looking.
 */
object ReadyAlert {
    fun shouldBuzz(wasReady: Boolean, current: WatchStatus): Boolean =
        current.canStopFromWatch && !wasReady

    /** The tap-to-stop prompt stays only while Stop would still work. */
    fun shouldClearPrompt(current: WatchStatus): Boolean = !current.canStopFromWatch
}
