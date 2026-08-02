package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conquest is the one place the app rewrites land the user already walked for,
 * so what it does to an older claim is pinned here — above all that being
 * swallowed archives a territory instead of deleting it.
 */
class ConquestTest {

    @Test
    fun `a claim that swallows another archives it instead of deleting it`() {
        val victim = square("small", ATHENS.offset(0.001, 0.001), sizeDeg = 0.001)
        val conqueror = square("big", ATHENS, sizeDeg = 0.005)

        val carved = Conquest.carve(listOf(victim), conqueror, atEpochMs = 999L)

        assertEquals(1, carved.size)
        val fallen = carved.single()
        assertEquals(victim.id, fallen.id)
        assertEquals(999L, fallen.conqueredAtEpochMs)
        assertEquals(conqueror.id, fallen.conqueredById)
        assertTrue("A conquered claim is no longer active", !fallen.isActive)
    }

    @Test
    fun `a conquered claim keeps the walk that earned it`() {
        val victim = square("small", ATHENS.offset(0.001, 0.001), sizeDeg = 0.001)
        val conqueror = square("big", ATHENS, sizeDeg = 0.005)

        val fallen = Conquest.carve(listOf(victim), conqueror, atEpochMs = 1L).single()

        // The record of the walk survives intact: same ring, same area as when
        // it fell. Losing these is exactly what deleting used to do.
        assertEquals(victim.ring, fallen.ring)
        assertEquals(victim.areaSqMeters, fallen.areaSqMeters, 0.001)
        assertEquals(victim.name, fallen.name)
    }

    @Test
    fun `a partial overlap shrinks the older claim and leaves it standing`() {
        val older = square("older", ATHENS, sizeDeg = 0.004)
        // Offset so it covers roughly a quarter of the older claim.
        val conqueror = square("newer", ATHENS.offset(0.002, 0.002), sizeDeg = 0.004)

        val reduced = Conquest.carve(listOf(older), conqueror, atEpochMs = 1L).single()

        assertTrue("Still on the map", reduced.isActive)
        assertNull(reduced.conqueredAtEpochMs)
        assertTrue(
            "Expected the claim to shrink, was ${reduced.areaSqMeters} of ${older.areaSqMeters}",
            reduced.areaSqMeters < older.areaSqMeters,
        )
        assertTrue("Some ground should survive", reduced.areaSqMeters > 0.0)
        // The as-walked ring is history and never gets rewritten by carving.
        assertEquals(older.ring, reduced.ring)
        // Stamped so SnapDisplay stops drawing a road-matched outline that still
        // describes the whole loop, part of which is now someone else's.
        assertEquals(1L, reduced.carvedAtEpochMs)
    }

    /**
     * A claim nobody has carved must stay unstamped, or every claim on the map
     * would refuse its matched outline.
     */
    @Test
    fun `an untouched claim is never stamped as carved`() {
        val older = square("older", ATHENS, sizeDeg = 0.004)
        val elsewhere = square("newer", BERLIN, sizeDeg = 0.004)

        assertTrue(Conquest.carve(listOf(older), elsewhere, atEpochMs = 1L).isEmpty())
        assertNull(older.carvedAtEpochMs)
    }

    @Test
    fun `claims that don't overlap are left completely alone`() {
        val faraway = square("berlin", BERLIN, sizeDeg = 0.004)
        val conqueror = square("athens", ATHENS, sizeDeg = 0.004)

        assertTrue(Conquest.carve(listOf(faraway), conqueror, atEpochMs = 1L).isEmpty())
    }

    @Test
    fun `a claim never conquers itself`() {
        val claim = square("self", ATHENS, sizeDeg = 0.004)

        assertTrue(Conquest.carve(listOf(claim), claim, atEpochMs = 1L).isEmpty())
    }

    @Test
    fun `an already conquered claim cannot fall twice`() {
        val fallen = square("fallen", ATHENS.offset(0.001, 0.001), sizeDeg = 0.001)
            .copy(conqueredAtEpochMs = 500L, conqueredById = "earlier")
        val conqueror = square("big", ATHENS, sizeDeg = 0.005)

        val carved = Conquest.carve(listOf(fallen), conqueror, atEpochMs = 999L)

        assertTrue("Already off the map; leave its record as it was", carved.isEmpty())
    }

    @Test
    fun `carving marks changed claims for re-sync`() {
        val victim = square("small", ATHENS.offset(0.001, 0.001), sizeDeg = 0.001)
            .copy(syncStatus = SyncStatus.SYNCED)
        val conqueror = square("big", ATHENS, sizeDeg = 0.005)

        val fallen = Conquest.carve(listOf(victim), conqueror, atEpochMs = 1L).single()

        assertEquals(SyncStatus.PENDING, fallen.syncStatus)
    }

    @Test
    fun `a degenerate conqueror carves nothing`() {
        val older = square("older", ATHENS, sizeDeg = 0.004)
        val twoPoints = older.copy(id = "sliver", ring = listOf(ATHENS, ATHENS.offset(0.0, 0.001)))

        assertTrue(Conquest.carve(listOf(older), twoPoints, atEpochMs = 1L).isEmpty())
    }

    @Test
    fun `only the claims actually touched come back`() {
        val overlapped = square("overlapped", ATHENS, sizeDeg = 0.004)
        val untouched = square("untouched", BERLIN, sizeDeg = 0.004)
        val conqueror = square("newer", ATHENS.offset(0.002, 0.002), sizeDeg = 0.004)

        val carved = Conquest.carve(listOf(overlapped, untouched), conqueror, atEpochMs = 1L)

        assertEquals(listOf("overlapped"), carved.map { it.id })
        assertNotNull(carved.single())
    }

    // --- helpers -------------------------------------------------------------

    private fun LatLng.offset(dLat: Double, dLng: Double) = LatLng(lat + dLat, lng + dLng)

    private fun square(id: String, at: LatLng, sizeDeg: Double): Territory {
        val ring = listOf(
            at,
            LatLng(at.lat, at.lng + sizeDeg),
            LatLng(at.lat + sizeDeg, at.lng + sizeDeg),
            LatLng(at.lat + sizeDeg, at.lng),
        )
        return Territory(
            id = id,
            name = "Claim $id",
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = Geo.pathLengthMeters(ring),
            claimedAtEpochMs = 0L,
        )
    }

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
        val BERLIN = LatLng(52.5200, 13.4050)
    }
}
