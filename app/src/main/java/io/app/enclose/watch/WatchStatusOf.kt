package io.app.enclose.watch

import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchStatus.Phase

/**
 * What the watch is told about the walk. Pure, so the rounding and the phase
 * rules are tested here and the Data Layer plumbing around it stays thin.
 *
 * A closed loop waiting to be claimed outranks everything: it is the one thing
 * on the watch that needs the user to take out their phone.
 */
fun watchStatusOf(
    walk: TrackingManager.WalkState,
    pending: TrackingManager.PendingClaim?,
): WatchStatus = when {
    pending != null -> WatchStatus(
        phase = Phase.CLOSED,
        closedAreaSqMeters = Math.round(pending.areaSqMeters).toInt(),
    )
    walk.isTracking -> WatchStatus(
        phase = Phase.WALKING,
        startedAtEpochMs = walk.startedAtMs,
        distanceMeters = WatchStatus.round(walk.distanceMeters, WatchStatus.DISTANCE_STEP_M),
        toStartMeters = walk.distanceToStartMeters
            ?.let { WatchStatus.round(it, WatchStatus.TO_START_STEP_M) },
        canCloseLoop = walk.canCloseLoop,
        readyToClose = walk.readyToClose,
        blocked = walk.motionBlocked,
        notRecording = walk.recordingFailure != null,
    )
    else -> WatchStatus()
}

/**
 * Whether opening the app from the watch's Start should begin a walk, given
 * the same three things the on-screen Start button weighs.
 *
 * When it says no, the app just opens on the map, where the panel already
 * explains what's missing and offers the fix: test mode asks before starting,
 * and a location problem needs the user's hands on the phone either way.
 */
fun shouldStartFromWatch(isTracking: Boolean, testMode: Boolean, canRecord: Boolean): Boolean =
    !isTracking && !testMode && canRecord
