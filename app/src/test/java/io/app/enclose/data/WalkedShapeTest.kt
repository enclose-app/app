package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What puts a cut claim back to the shape it was walked with — run once over
 * every claim by `MIGRATION_13_14`, and on every backup restore. The rule worth
 * pinning hardest is the last one: a claim that was never cut is left alone.
 */
class WalkedShapeTest {

    private val ring = listOf(
        ATHENS,
        LatLng(ATHENS.lat, ATHENS.lng + 0.004),
        LatLng(ATHENS.lat + 0.004, ATHENS.lng + 0.004),
        LatLng(ATHENS.lat + 0.004, ATHENS.lng),
    )

    @Test
    fun `a carved claim comes back whole, with the area it had at Stop`() {
        // The western half left, after a later claim took the eastern one.
        val bitten = Territory.polygonsFromRing(
            listOf(
                ATHENS,
                LatLng(ATHENS.lat, ATHENS.lng + 0.002),
                LatLng(ATHENS.lat + 0.004, ATHENS.lng + 0.002),
                LatLng(ATHENS.lat + 0.004, ATHENS.lng),
            ),
        )

        val shape = WalkedShape.restore(ring, polygons = bitten, cut = true)

        assertNotNull(shape)
        assertEquals(Territory.polygonsFromRing(ring), shape!!.polygons)
        assertEquals(Geo.polygonAreaSqMeters(ring), shape.areaSqMeters, 0.0)
    }

    /** Claims cut before the carve stamp existed have no record of it. */
    @Test
    fun `a cut claim with no stamp is found by its shape`() {
        // Split in two by a later claim across the middle.
        val split = listOf(
            listOf(listOf(ATHENS, LatLng(ATHENS.lat, ATHENS.lng + 0.001), LatLng(ATHENS.lat + 0.001, ATHENS.lng))),
            listOf(
                listOf(
                    LatLng(ATHENS.lat + 0.003, ATHENS.lng + 0.003),
                    LatLng(ATHENS.lat + 0.003, ATHENS.lng + 0.004),
                    LatLng(ATHENS.lat + 0.004, ATHENS.lng + 0.004),
                ),
            ),
        )

        val shape = WalkedShape.restore(ring, polygons = split, cut = false)

        assertEquals(Territory.polygonsFromRing(ring), shape?.polygons)
    }

    /** Swallowed whole, it kept the shape it had when it fell — sometimes its own. */
    @Test
    fun `a conquered claim comes back even when its shape was never cut`() {
        val shape = WalkedShape.restore(ring, polygons = Territory.polygonsFromRing(ring), cut = true)

        assertNotNull("The stamp alone is enough to clear", shape)
    }

    @Test
    fun `an unreadable shape is rebuilt from the ring`() {
        val shape = WalkedShape.restore(ring, polygons = emptyList(), cut = false)

        assertEquals(Territory.polygonsFromRing(ring), shape?.polygons)
    }

    @Test
    fun `a claim that was never cut is left exactly as it is`() {
        assertNull(WalkedShape.restore(ring, polygons = Territory.polygonsFromRing(ring), cut = false))
    }

    @Test
    fun `a claim with no ring to restore from is never replaced with nothing`() {
        val bitten = Territory.polygonsFromRing(ring)

        assertNull(WalkedShape.restore(emptyList(), polygons = bitten, cut = true))
        assertNull(WalkedShape.restore(ring.take(2), polygons = bitten, cut = true))
    }

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
