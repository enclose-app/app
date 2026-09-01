package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a tap on the map selects. The rule that matters most here is the one
 * about *which* geometry is tested: the map draws the snapped outline when
 * there is one, so a hit test against the as-walked ring would answer for a
 * shape nobody can see.
 */
class TerritoryHitTest {

    @Test
    fun `a tap inside a claim selects it`() {
        val claim = square("a", ATHENS, sizeDeg = 0.004)

        assertEquals(claim, TerritoryHit.at(ATHENS.offset(0.002, 0.002), listOf(claim)))
    }

    @Test
    fun `a tap on open ground selects nothing`() {
        val claim = square("a", ATHENS, sizeDeg = 0.004)

        assertNull(TerritoryHit.at(ATHENS.offset(0.02, 0.02), listOf(claim)))
        assertNull(TerritoryHit.at(ATHENS, emptyList()))
    }

    @Test
    fun `a tap in a hole carved out of a claim misses it`() {
        val exterior = ring(ATHENS, sizeDeg = 0.006)
        val hole = ring(ATHENS.offset(0.002, 0.002), sizeDeg = 0.002)
        val carved = square("carved", ATHENS, sizeDeg = 0.006)
            .copy(polygons = listOf(listOf(exterior, hole)))

        // Inside the outer ring, but inside the hole: this is ground the claim
        // lost, and tapping it must not answer with the claim that lost it.
        assertNull(TerritoryHit.at(ATHENS.offset(0.003, 0.003), listOf(carved)))
        assertEquals(carved, TerritoryHit.at(ATHENS.offset(0.0005, 0.0005), listOf(carved)))
    }

    @Test
    fun `the smaller claim wins where two cover the same point`() {
        val big = square("big", ATHENS, sizeDeg = 0.006)
        val small = square("small", ATHENS.offset(0.001, 0.001), sizeDeg = 0.001)
        val point = small.ring.let { LatLng(it[0].lat + 0.0005, it[0].lng + 0.0005) }

        assertEquals(small, TerritoryHit.at(point, listOf(big, small)))
        assertEquals("Order of the list must not decide it", small, TerritoryHit.at(point, listOf(small, big)))
    }

    @Test
    fun `the hit test follows the outline the map draws`() {
        // Walked here, matched onto roads over there. The map draws the matched
        // ring (SnapDisplay), so that is the shape a finger meets.
        val walked = square("snapped", ATHENS, sizeDeg = 0.004)
        val claim = walked.copy(snappedRing = ring(ATHENS.offset(0.01, 0.01), sizeDeg = 0.004))

        assertNull(TerritoryHit.at(ATHENS.offset(0.002, 0.002), listOf(claim)))
        assertEquals(claim, TerritoryHit.at(ATHENS.offset(0.012, 0.012), listOf(claim)))
    }

    @Test
    fun `a claim whose stored geometry is unreadable is still selectable`() {
        // polygons empty is what a decode failure looks like; SnapDisplay falls
        // back to the ring, and so must the hit test — a claim you can see and
        // cannot tap reads as the map being broken.
        val claim = square("ringOnly", ATHENS, sizeDeg = 0.004).copy(polygons = emptyList())

        assertEquals(claim, TerritoryHit.at(ATHENS.offset(0.002, 0.002), listOf(claim)))
    }

    // --- helpers -------------------------------------------------------------

    private fun LatLng.offset(dLat: Double, dLng: Double) = LatLng(lat + dLat, lng + dLng)

    private fun ring(at: LatLng, sizeDeg: Double): List<LatLng> = listOf(
        at,
        LatLng(at.lat, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng),
    )

    private fun square(id: String, at: LatLng, sizeDeg: Double): Territory {
        val ring = ring(at, sizeDeg)
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
    }
}
