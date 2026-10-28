package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ground a set of claims covers. Claims are layers and can overlap, so
 * every total in the app comes from here — and the case pinned hardest is the
 * one that has to read exactly as it did before: claims that don't overlap add
 * up to the sum of their own areas, to the square metre.
 */
class FootprintTest {

    @Test
    fun `claims that don't overlap total exactly their sum`() {
        val a = square("a", ATHENS, sizeDeg = 0.004)
        val b = square("b", ATHENS.offset(0.01, 0.0), sizeDeg = 0.002)

        assertEquals(a.areaSqMeters + b.areaSqMeters, Footprint.areaSqMeters(listOf(a, b)), 0.0)
    }

    /** Boxes that touch put claims in one cluster; that alone must cost nothing. */
    @Test
    fun `claims whose boxes touch but whose ground doesn't still total their sum`() {
        val a = square("a", ATHENS, sizeDeg = 0.004)
        val beside = square("b", ATHENS.offset(0.0, 0.004), sizeDeg = 0.004)

        assertEquals(a.areaSqMeters + beside.areaSqMeters, Footprint.areaSqMeters(listOf(a, beside)), 1.0)
    }

    @Test
    fun `two claims sharing ground total their union`() {
        val a = square("a", ATHENS, sizeDeg = 0.004)
        val b = square("b", ATHENS.offset(0.0, 0.002), sizeDeg = 0.004)

        // b's western half lies over a: one and a half squares between them.
        assertEquals(a.areaSqMeters * 1.5, Footprint.areaSqMeters(listOf(a, b)), a.areaSqMeters * 0.01)
    }

    @Test
    fun `a claim inside another adds nothing`() {
        val big = square("big", ATHENS, sizeDeg = 0.006)
        val inside = square("inside", ATHENS.offset(0.002, 0.002), sizeDeg = 0.001)

        assertEquals(big.areaSqMeters, Footprint.areaSqMeters(listOf(big, inside)), big.areaSqMeters * 0.01)
    }

    @Test
    fun `the same loop claimed twice counts once`() {
        val first = square("first", ATHENS, sizeDeg = 0.004)
        val again = first.copy(id = "again", claimedAtEpochMs = 9L)

        assertEquals(first.areaSqMeters, Footprint.areaSqMeters(listOf(first, again)), first.areaSqMeters * 0.01)
    }

    /** A chain links its ends into one cluster through the middle claim. */
    @Test
    fun `overlap is found through a chain of claims`() {
        val west = square("west", ATHENS, sizeDeg = 0.004)
        val middle = square("middle", ATHENS.offset(0.0, 0.003), sizeDeg = 0.004)
        val east = square("east", ATHENS.offset(0.0, 0.006), sizeDeg = 0.004)

        // Three squares, with a quarter of a square shared at each of two seams.
        val expected = west.areaSqMeters * 2.5
        assertEquals(expected, Footprint.areaSqMeters(listOf(west, east, middle)), west.areaSqMeters * 0.01)
    }

    @Test
    fun `claims in two cities never share ground`() {
        val athens = square("athens", ATHENS, sizeDeg = 0.004)
        val berlin = square("berlin", BERLIN, sizeDeg = 0.004)

        assertEquals(athens.areaSqMeters + berlin.areaSqMeters, Footprint.areaSqMeters(listOf(athens, berlin)), 0.0)
    }

    @Test
    fun `the total never reads less than its biggest claim`() {
        val big = square("big", ATHENS, sizeDeg = 0.006)
        val copies = (0 until 5).map { big.copy(id = "copy$it") }

        assertTrue(Footprint.areaSqMeters(listOf(big) + copies) >= big.areaSqMeters)
    }

    @Test
    fun `nothing covers nothing`() {
        assertEquals(0.0, Footprint.areaSqMeters(emptyList()), 0.0)
    }

    @Test
    fun `a claim with no geometry is counted by its stored area`() {
        val empty = square("empty", ATHENS, sizeDeg = 0.004).copy(ring = emptyList(), polygons = emptyList())
        val real = square("real", ATHENS, sizeDeg = 0.004)

        assertEquals(empty.areaSqMeters + real.areaSqMeters, Footprint.areaSqMeters(listOf(empty, real)), 0.0)
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
