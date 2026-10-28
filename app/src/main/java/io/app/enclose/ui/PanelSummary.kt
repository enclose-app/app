package io.app.enclose.ui

import io.app.enclose.tracking.TrackingManager

/**
 * What the bottom control panel is currently for.
 *
 * Three different surfaces now render the same walk — the full panel, the
 * collapsed one-line panel, and the floating (picture-in-picture) card — and
 * before this they each had their own `when` over [TrackingManager.WalkState].
 * That is how a panel ends up offering "Close loop & claim" while the card
 * beside it still says the walk is in progress. The decision is made once, here,
 * in plain Kotlin so it can be unit tested; the surfaces only choose how to draw
 * it.
 */
internal enum class PanelStatus {
    /** No walk in progress; the panel offers to start one. */
    IDLE,

    /**
     * Location can't produce a fix worth recording — refused, granted only as
     * approximate, or switched off device-wide. Without it there is nothing to
     * record, so the panel offers the repair instead of a Start button that would
     * begin a walk incapable of recording anything.
     */
    NO_LOCATION,

    /** Recording normally. */
    TRACKING,

    /**
     * Recording as normal, through a stretch nothing vouches for as walked: it
     * will be a straight line in the claim unless steps turn up for it. Nothing
     * is paused or at risk — this only says why part of the trail is dotted.
     */
    DOUBTFUL,

    /** Stopping right now would claim a valid loop. */
    READY,
    ;

    /** True for every state in which a walk is under way, however it's going. */
    val isTracking: Boolean get() = this == TRACKING || this == DOUBTFUL || this == READY
}

/** The one action the panel leads with, collapsed or not. */
internal enum class PanelAction {
    START,
    CLAIM,
    END,
    GRANT_PERMISSION,

    /** This app's settings page — where the Precise location toggle lives. */
    OPEN_SETTINGS,

    /** The device's location settings, for the master switch. */
    OPEN_LOCATION_SETTINGS,
}

internal data class PanelSummary(
    val status: PanelStatus,
    val action: PanelAction,
) {
    companion object {
        /**
         * A walk in progress always owns the panel, even when location
         * permission has been revoked mid-walk: hiding the controls for the walk
         * someone is *on* is the worse failure, since the way to save it is the
         * Stop button that would disappear.
         */
        fun of(
            walk: TrackingManager.WalkState,
            testMode: Boolean,
            location: LocationReadiness,
        ): PanelSummary = when {
            walk.isTracking -> {
                val status = when {
                    // Ready outranks doubtful: someone standing at the start can
                    // claim the loop now, and the claim dialog shows what was
                    // bridged. Saying "doubtful" instead would hide the one
                    // button they came back for.
                    walk.readyToClose -> PanelStatus.READY
                    walk.doubtful -> PanelStatus.DOUBTFUL
                    else -> PanelStatus.TRACKING
                }
                PanelSummary(
                    status = status,
                    action = if (status == PanelStatus.READY) PanelAction.CLAIM else PanelAction.END,
                )
            }

            // Test mode feeds points from map taps, so it needs no GPS at all.
            !location.canRecord && !testMode -> PanelSummary(
                status = PanelStatus.NO_LOCATION,
                // Each way of being un-ready has a different repair, and offering
                // the wrong one is a button that does nothing: re-prompting for a
                // permission the system won't ask about again, or asking for
                // permission when the problem is the device's own switch.
                action = when (location) {
                    LocationReadiness.DENIED -> PanelAction.GRANT_PERMISSION
                    LocationReadiness.SERVICES_OFF -> PanelAction.OPEN_LOCATION_SETTINGS
                    // Approximate is upgraded to precise on this app's settings
                    // page; the runtime prompt can't be relied on to offer it.
                    LocationReadiness.BLOCKED,
                    LocationReadiness.APPROXIMATE_ONLY,
                    -> PanelAction.OPEN_SETTINGS
                    // Unreachable: canRecord is exactly READY.
                    LocationReadiness.READY -> PanelAction.START
                },
            )

            else -> PanelSummary(status = PanelStatus.IDLE, action = PanelAction.START)
        }
    }
}
