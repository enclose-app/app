package io.app.enclose.tracking

import io.app.enclose.geo.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPlausibilityTest {

    @Test
    fun `a walking step is not a jump`() {
        assertFalse(jump(meters = 5.0, ms = 3_000L))
    }

    @Test
    fun `hundreds of metres in one interval is a jump`() {
        assertTrue(jump(meters = 300.0, ms = 3_000L))
    }

    @Test
    fun `the same distance after a long silence is not`() {
        assertFalse(jump(meters = 300.0, ms = 240_000L))
    }

    @Test
    fun `stated accuracy widens what is reachable`() {
        assertTrue(jump(meters = 100.0, ms = 3_000L, accuracy = 5f))
        assertFalse(jump(meters = 100.0, ms = 3_000L, accuracy = 20f))
    }

    @Test
    fun `fixes in a burst are not judged on GPS wander`() {
        assertFalse(jump(meters = 25.0, ms = 0L))
    }

    private fun jump(meters: Double, ms: Long, accuracy: Float? = 5f) = FixPlausibility.isJump(
        ORIGIN, 0L, accuracy,
        LatLng(ORIGIN.lat + meters / 111_195.0, ORIGIN.lng), ms, accuracy,
    )

    private companion object {
        val ORIGIN = LatLng(37.9838, 23.7275)
    }
}
