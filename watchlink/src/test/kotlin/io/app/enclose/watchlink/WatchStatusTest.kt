package io.app.enclose.watchlink

import io.app.enclose.watchlink.WatchStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the watch update separately, so this encoding is the contract
 * between two apps that may be different versions. A watch that misreads the
 * phone could offer Stop on a walk that isn't closable.
 */
class WatchStatusTest {

    private fun roundTrip(status: WatchStatus): WatchStatus? {
        val fields = status.toFields()
        return WatchStatus.fromFields { fields[it] }
    }

    @Test
    fun `a full status survives the round trip`() {
        val status = WatchStatus(
            phase = Phase.WALKING,
            startedAtEpochMs = 1_790_000_000_000L,
            distanceMeters = 1230,
            toStartMeters = 15,
            canCloseLoop = true,
            readyToClose = true,
            blocked = false,
            notRecording = false,
        )
        assertEquals(status, roundTrip(status))
    }

    @Test
    fun `absent nullables stay absent`() {
        val status = WatchStatus(phase = Phase.CLOSED, closedAreaSqMeters = 52_000)
        val back = roundTrip(status)!!
        assertNull(back.startedAtEpochMs)
        assertNull(back.toStartMeters)
        assertEquals(52_000, back.closedAreaSqMeters)
    }

    @Test
    fun `a different version is unreadable, not guessed at`() {
        val fields = WatchStatus(phase = Phase.WALKING).toFields() + ("v" to WatchStatus.VERSION + 1)
        assertNull(WatchStatus.fromFields { fields[it] })
    }

    @Test
    fun `an unknown phase is unreadable`() {
        val fields = WatchStatus().toFields() + ("phase" to "SAILING")
        assertNull(WatchStatus.fromFields { fields[it] })
    }

    @Test
    fun `a missing required field is unreadable`() {
        val fields = WatchStatus(phase = Phase.WALKING).toFields() - "ready"
        assertNull(WatchStatus.fromFields { fields[it] })
    }

    @Test
    fun `nothing at all is unreadable`() {
        assertNull(WatchStatus.fromFields { null })
    }

    @Test
    fun `stop is offered only on a walk that is ready to close`() {
        assertTrue(WatchStatus(phase = Phase.WALKING, readyToClose = true).canStopFromWatch)
        assertFalse(WatchStatus(phase = Phase.WALKING, canCloseLoop = true).canStopFromWatch)
        assertFalse(WatchStatus(phase = Phase.CLOSED, readyToClose = true).canStopFromWatch)
        assertFalse(WatchStatus(phase = Phase.IDLE).canStopFromWatch)
    }

    @Test
    fun `rounding snaps to the step`() {
        assertEquals(1230, WatchStatus.round(1234.0, 10))
        assertEquals(1240, WatchStatus.round(1235.0, 10))
        assertEquals(15, WatchStatus.round(13.0, 5))
        assertEquals(0, WatchStatus.round(2.0, 5))
    }
}
