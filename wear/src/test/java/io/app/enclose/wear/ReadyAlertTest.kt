package io.app.enclose.wear

import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchStatus.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyAlertTest {

    private val ready = WatchStatus(phase = Phase.WALKING, readyToClose = true)
    private val walking = WatchStatus(phase = Phase.WALKING, canCloseLoop = true)

    @Test
    fun `buzzes on becoming ready`() {
        assertTrue(ReadyAlert.shouldBuzz(wasReady = false, current = ready))
    }

    @Test
    fun `does not buzz again while still ready`() {
        assertFalse(ReadyAlert.shouldBuzz(wasReady = true, current = ready))
    }

    @Test
    fun `does not buzz on leaving the closing radius`() {
        assertFalse(ReadyAlert.shouldBuzz(wasReady = true, current = walking))
    }

    @Test
    fun `the prompt goes once stop would no longer work`() {
        assertFalse(ReadyAlert.shouldClearPrompt(ready))
        assertTrue(ReadyAlert.shouldClearPrompt(walking))
        assertTrue(ReadyAlert.shouldClearPrompt(WatchStatus(phase = Phase.CLOSED)))
    }

    private val ridingOn = WatchStatus(phase = Phase.WALKING, doubtful = true, loopClosedAtEpochMs = 5_000L)

    @Test
    fun `stop being offered at any time doesn't make every walk ready`() {
        // Stop is offered all walk long now; the buzz still waits for the loop.
        assertFalse(ReadyAlert.shouldBuzz(wasReady = false, current = WatchStatus(phase = Phase.WALKING)))
    }

    @Test
    fun `warns once when a stretch turns doubtful after the loop closed`() {
        assertTrue(ReadyAlert.shouldWarnDoubt(warnedFor = null, current = ridingOn))
        assertFalse("Not again for the same loop", ReadyAlert.shouldWarnDoubt(warnedFor = 5_000L, current = ridingOn))
    }

    @Test
    fun `a later loop is a new warning`() {
        val later = ridingOn.copy(loopClosedAtEpochMs = 9_000L)
        assertTrue(ReadyAlert.shouldWarnDoubt(warnedFor = 5_000L, current = later))
    }

    @Test
    fun `no warning without a doubt or without a loop`() {
        assertFalse(ReadyAlert.shouldWarnDoubt(null, ridingOn.copy(doubtful = false)))
        assertFalse(ReadyAlert.shouldWarnDoubt(null, ridingOn.copy(loopClosedAtEpochMs = null)))
        assertFalse(ReadyAlert.shouldWarnDoubt(null, ridingOn.copy(phase = Phase.CLOSED)))
    }
}
