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

    /** Location was refused, and without it there is nothing to record. */
    NO_PERMISSION,

    /** Recording normally. */
    TRACKING,

    /** Recording, but movement is being rejected as not human-powered. */
    BLOCKED,

    /** Stopping right now would claim a valid loop. */
    READY,
    ;

    /** True for every state in which a walk is under way, however it's going. */
    val isTracking: Boolean get() = this == TRACKING || this == BLOCKED || this == READY
}

/** The one action the panel leads with, collapsed or not. */
internal enum class PanelAction {
    START,
    CLAIM,
    END,
    GRANT_PERMISSION,
    OPEN_SETTINGS,
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
            hasLocationPermission: Boolean,
            permissionBlocked: Boolean,
        ): PanelSummary = when {
            walk.isTracking -> {
                val status = when {
                    // Blocked outranks ready: the loop can't close while movement
                    // is being rejected, and readyToClose is already false then.
                    walk.motionBlocked -> PanelStatus.BLOCKED
                    walk.readyToClose -> PanelStatus.READY
                    else -> PanelStatus.TRACKING
                }
                PanelSummary(
                    status = status,
                    action = if (status == PanelStatus.READY) PanelAction.CLAIM else PanelAction.END,
                )
            }

            // Test mode feeds points from map taps, so it needs no GPS at all.
            !hasLocationPermission && !testMode -> PanelSummary(
                status = PanelStatus.NO_PERMISSION,
                action = if (permissionBlocked) {
                    // The system prompt will no longer appear, so asking again
                    // would be a button that does nothing.
                    PanelAction.OPEN_SETTINGS
                } else {
                    PanelAction.GRANT_PERMISSION
                },
            )

            else -> PanelSummary(status = PanelStatus.IDLE, action = PanelAction.START)
        }
    }
}
