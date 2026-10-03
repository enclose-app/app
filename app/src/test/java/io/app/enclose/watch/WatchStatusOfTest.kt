package io.app.enclose.watch

import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.BlockReason
import io.app.enclose.tracking.RecordingFailure
import io.app.enclose.tracking.TrackingManager.PendingClaim
import io.app.enclose.tracking.TrackingManager.WalkState
import io.app.enclose.watchlink.WatchStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the watch is told decides whether it offers Stop, so a wrong answer here
 * is a button on the wrist that claims, or throws away, the wrong walk.
 */
class WatchStatusOfTest {

    private fun pending(area: Double = 51_234.6) = PendingClaim(
        id = "w1",
        ring = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0)),
        areaSqMeters = area,
        perimeterMeters = 900.0,
        distanceToStartMeters = 12.0,
        closedAtEpochMs = 2_000L,
        startedAtEpochMs = 1_000L,
        elevationGainMeters = 0.0,
        movingMs = 1_000L,
        hadSignalGap = false,
        suggestedName = "Loop",
    )

    @Test
    fun `no walk is idle`() {
        val status = watchStatusOf(WalkState(), null)
        assertEquals(Phase.IDLE, status.phase)
        assertFalse(status.canStopFromWatch)
    }

    @Test
    fun `a walk in progress carries rounded figures`() {
        val status = watchStatusOf(
            WalkState(
                isTracking = true,
                startedAtMs = 1_000L,
                distanceMeters = 1_234.0,
                distanceToStartMeters = 13.0,
                canCloseLoop = true,
            ),
            null,
        )
        assertEquals(Phase.WALKING, status.phase)
        assertEquals(1_000L, status.startedAtEpochMs)
        assertEquals(1_230, status.distanceMeters)
        assertEquals(15, status.toStartMeters)
        assertTrue(status.canCloseLoop)
        assertFalse(status.canStopFromWatch)
    }

    @Test
    fun `stop is offered only once the loop is ready to close`() {
        val status = watchStatusOf(WalkState(isTracking = true, readyToClose = true), null)
        assertTrue(status.canStopFromWatch)
    }

    @Test
    fun `a closed loop outranks the walk`() {
        val status = watchStatusOf(WalkState(isTracking = true, readyToClose = true), pending())
        assertEquals(Phase.CLOSED, status.phase)
        assertEquals(51_235, status.closedAreaSqMeters)
        assertFalse(status.canStopFromWatch)
    }

    @Test
    fun `before the first fix there is no distance to start`() {
        val status = watchStatusOf(WalkState(isTracking = true), null)
        assertNull(status.toStartMeters)
        assertNull(status.startedAtEpochMs)
    }

    @Test
    fun `blocking and a dead recorder are passed on`() {
        val status = watchStatusOf(
            WalkState(
                isTracking = true,
                blockedReason = BlockReason.VEHICLE,
                recordingFailure = RecordingFailure.PERMISSION,
            ),
            null,
        )
        assertTrue(status.blocked)
        assertTrue(status.notRecording)
    }

    @Test
    fun `watch start begins a walk only when the start button would`() {
        assertTrue(shouldStartFromWatch(isTracking = false, testMode = false, canRecord = true))
        assertFalse(shouldStartFromWatch(isTracking = true, testMode = false, canRecord = true))
        assertFalse(shouldStartFromWatch(isTracking = false, testMode = true, canRecord = true))
        assertFalse(shouldStartFromWatch(isTracking = false, testMode = false, canRecord = false))
    }
}
