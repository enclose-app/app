package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared activity sets the speed ceiling the whole anti-cheat hangs off,
 * so which modes are selectable — and what a stored one resolves to — is pinned
 * rather than left to the UI to enforce.
 */
class ActivityTypeTest {

    @Test
    fun `walking is the mode that is currently available`() {
        assertTrue(ActivityType.WALK.available)
        assertFalse(ActivityType.RUN.available)
        assertFalse(ActivityType.BIKE.available)
    }

    @Test
    fun `a stored available mode is kept`() {
        assertEquals(ActivityType.WALK, ActivityType.resolve("WALK"))
    }

    /**
     * Someone who chose BIKE before it was turned off must not be left walking
     * under a cycling ceiling they can no longer see or change.
     */
    @Test
    fun `a stored mode that is no longer available falls back to walking`() {
        assertEquals(ActivityType.WALK, ActivityType.resolve("BIKE"))
        assertEquals(ActivityType.WALK, ActivityType.resolve("RUN"))
    }

    @Test
    fun `an unknown or missing name falls back to walking`() {
        assertEquals(ActivityType.WALK, ActivityType.resolve("SKATEBOARD"))
        assertEquals(ActivityType.WALK, ActivityType.resolve(null))
        assertEquals(ActivityType.WALK, ActivityType.resolve(""))
    }
}
