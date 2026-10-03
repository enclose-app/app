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
}
