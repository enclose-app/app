package io.app.enclose.watchlink

/**
 * The phone ↔ watch protocol, over the Wearable Data Layer.
 *
 * The phone owns the walk — GPS, the claim, the database — and the watch is a
 * window onto it with two buttons, and a second witness to it. So the traffic is
 * lopsided on purpose: data items the phone keeps current ([STATUS_PATH],
 * [MAP_PATH], [CLAIMS_PATH]), and two messages from the watch — Stop
 * ([STOP_PATH]) and the steps it counted ([STEPS_PATH]), which the phone can ask
 * for at Stop ([STEPS_FLUSH_PATH]). Starting a walk is not a message at all:
 * Android won't let a background app begin location tracking, so the watch opens
 * the phone app at [START_URI] instead and the walk starts there, behind the same
 * checks as the on-screen Start button.
 *
 * Both apps must share an applicationId and signing key for the Data Layer to
 * connect them at all.
 */
object WatchLink {
    /** Data item holding the latest [WatchStatus]. Written by the phone only. */
    const val STATUS_PATH = "/enclose/status"

    /** Data item holding the walk's [WatchMap]. Written by the phone only. */
    const val MAP_PATH = "/enclose/map"

    /** Data item holding [WatchClaims] near the walk. Written by the phone only. */
    const val CLAIMS_PATH = "/enclose/claims"

    /**
     * Message asking the phone to stop and claim. Only ever honoured when the
     * phone's own view of the walk says the loop is ready to close — see
     * [WatchStatus.canStopFromWatch].
     */
    const val STOP_PATH = "/enclose/stop"

    /**
     * Message carrying [WatchSteps]: what the watch counted during the walk.
     * Sent by the watch only — after each batch Health Services delivers, and in
     * answer to [STEPS_FLUSH_PATH].
     */
    const val STEPS_PATH = "/enclose/steps"

    /**
     * Message asking the watch for every step it is still holding. Sent by the
     * phone at Stop, and only when a stretch is in doubt: it is the one thing
     * Stop waits for, and the watch's steps can change nothing else. Carries the
     * walk it asks about — see [WatchSteps.flushRequest].
     */
    const val STEPS_FLUSH_PATH = "/enclose/steps/flush"

    /** Opens Enclose on the phone and starts a walk if location is ready. */
    const val START_URI = "enclose://walk/start"

    /**
     * Opens Enclose on the phone and nothing more — for a closed loop, which is
     * named and claimed there. Must never start a walk: the phone would begin
     * a new one on top of the claim it was opened to make.
     */
    const val OPEN_URI = "enclose://walk/open"

    const val SCHEME = "enclose"
    const val START_PATH = "/start"
}
