package io.app.enclose.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two states this enum exists for — approximate-only and services-off — were
 * both previously read as "granted", which is how a walk could start, hold a
 * foreground service, and record nothing at all.
 */
class LocationReadinessTest {

    private fun readiness(
        precise: Boolean = true,
        approximate: Boolean = true,
        servicesEnabled: Boolean = true,
        promptBlocked: Boolean = false,
    ) = LocationReadiness.of(precise, approximate, servicesEnabled, promptBlocked)

    @Test
    fun `precise location with services on is ready`() {
        assertEquals(LocationReadiness.READY, readiness())
        assertTrue(readiness().canRecord)
    }

    /** Android grants coarse alongside fine, so this is the ordinary granted case. */
    @Test
    fun `precise outranks the coarse grant that comes with it`() {
        assertEquals(LocationReadiness.READY, readiness(precise = true, approximate = true))
    }

    @Test
    fun `approximate on its own cannot record`() {
        val result = readiness(precise = false, approximate = true)
        assertEquals(LocationReadiness.APPROXIMATE_ONLY, result)
        assertFalse(result.canRecord)
    }

    /** Vague is still worth drawing on the map, just not worth claiming with. */
    @Test
    fun `approximate still lets the map show a position`() {
        assertTrue(readiness(precise = false, approximate = true).hasPermission)
    }

    @Test
    fun `the device switch beats the permission`() {
        val result = readiness(servicesEnabled = false)
        assertEquals(LocationReadiness.SERVICES_OFF, result)
        assertFalse(result.canRecord)
        // The permission is genuinely held — only the switch is off.
        assertTrue(result.hasPermission)
    }

    @Test
    fun `nothing granted is denied while the prompt still works`() {
        val result = readiness(precise = false, approximate = false)
        assertEquals(LocationReadiness.DENIED, result)
        assertFalse(result.hasPermission)
    }

    @Test
    fun `nothing granted and no prompt left is blocked`() {
        assertEquals(
            LocationReadiness.BLOCKED,
            readiness(precise = false, approximate = false, promptBlocked = true),
        )
    }

    /**
     * A denial that also has the device switch off still reports the denial: the
     * permission is the first thing the user can act on, and granting it is what
     * makes the switch worth mentioning.
     */
    @Test
    fun `a denial is reported ahead of the device switch`() {
        assertEquals(
            LocationReadiness.DENIED,
            readiness(precise = false, approximate = false, servicesEnabled = false),
        )
    }
}
