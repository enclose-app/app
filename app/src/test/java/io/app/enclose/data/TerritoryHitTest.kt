package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a tap on the map selects. Two rules matter most here, and both come down
 * to answering for what the finger can see: *which* geometry is tested — the
 * map draws the snapped outline when there is one — and, since claims are
 * layers, *which* of several stacked claims a tap means.
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
    fun `a tap in a hole in a claim's geometry misses it`() {
        val exterior = ring(ATHENS, sizeDeg = 0.006)
        val hole = ring(ATHENS.offset(0.002, 0.002), sizeDeg = 0.002)
        val holed = square("holed", ATHENS, sizeDeg = 0.006)
            .copy(polygons = listOf(listOf(exterior, hole)))

        // Inside the outer ring, but inside the hole: the geometry doesn't cover
        // it, so neither does the claim.
        assertNull(TerritoryHit.at(ATHENS.offset(0.003, 0.003), listOf(holed)))
        assertEquals(holed, TerritoryHit.at(ATHENS.offset(0.0005, 0.0005), listOf(holed)))
    }

    /** Claims are stacked oldest first, so the newest is the one on top. */
    @Test
    fun `the newest claim wins where two cover the same point`() {
        val older = square("older", ATHENS, sizeDeg = 0.006, claimedAt = 1_000L)
        val newer = square("newer", ATHENS.offset(0.001, 0.001), sizeDeg = 0.006, claimedAt = 2_000L)
        val overlap = ATHENS.offset(0.003, 0.003)

        assertEquals(newer, TerritoryHit.at(overlap, listOf(older, newer)))
        assertEquals("Order of the list must not decide it", newer, TerritoryHit.at(overlap, listOf(newer, older)))
    }

    /**
     * A big new claim drawn over a small old one wins inside the small one's
     * outline too: it is on top there. The small one is reached from the list.
     */
    @Test
    fun `a newer claim on top wins over a smaller one underneath`() {
        val small = square("small", ATHENS.offset(0.002, 0.002), sizeDeg = 0.001, claimedAt = 1_000L)
        val big = square("big", ATHENS, sizeDeg = 0.006, claimedAt = 2_000L)

        assertEquals(big, TerritoryHit.at(ATHENS.offset(0.0025, 0.0025), listOf(small, big)))
    }

    /** The map draws the selection above every claim, so it is on top where it is. */
    @Test
    fun `the selected claim wins where the tap is inside it`() {
        val older = square("older", ATHENS, sizeDeg = 0.006, claimedAt = 1_000L)
        val newer = square("newer", ATHENS.offset(0.001, 0.001), sizeDeg = 0.006, claimedAt = 2_000L)
        val overlap = ATHENS.offset(0.003, 0.003)

        assertEquals(older, TerritoryHit.at(overlap, listOf(older, newer), selectedId = "older"))
    }

    @Test
    fun `a selection the tap misses does not win`() {
        val selected = square("selected", ATHENS.offset(0.02, 0.02), sizeDeg = 0.004, claimedAt = 3_000L)
        val older = square("older", ATHENS, sizeDeg = 0.006, claimedAt = 1_000L)

        assertEquals(older, TerritoryHit.at(ATHENS.offset(0.003, 0.003), listOf(older, selected), selectedId = "selected"))
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

    private fun square(id: String, at: LatLng, sizeDeg: Double, claimedAt: Long = 0L): Territory {
        val ring = ring(at, sizeDeg)
        return Territory(
            id = id,
            name = "Claim $id",
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = Geo.pathLengthMeters(ring),
            claimedAtEpochMs = claimedAt,
        )
    }

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
