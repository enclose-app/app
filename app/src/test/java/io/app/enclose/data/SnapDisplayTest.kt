package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place that decides which outline a claim is drawn with: the matched
 * one when there is a usable one, and otherwise the claim's own geometry, with
 * the as-walked ring behind that so a claim always draws as something.
 */
class SnapDisplayTest {

    @Test
    fun `an unmatched claim draws its own geometry`() {
        val t = territory()

        assertFalse(SnapDisplay.usesSnapped(t))
        assertEquals(t.polygons, SnapDisplay.polygonsFor(t))
    }

    @Test
    fun `a matched claim draws the matched outline`() {
        val snapped = square(SNAPPED_ORIGIN, SIDE_DEG)
        val t = territory(snappedRing = snapped, snappedAtEpochMs = 1_000L)

        assertTrue(SnapDisplay.usesSnapped(t))
        assertEquals(Territory.polygonsFromRing(snapped), SnapDisplay.polygonsFor(t))
    }

    /**
     * A refusal stores a timestamp and no ring. That must read as "not matched",
     * not as "matched to nothing".
     */
    @Test
    fun `a refused match draws the claim's own geometry`() {
        val t = territory(snappedRing = emptyList(), snappedAtEpochMs = 5_000L)

        assertFalse(SnapDisplay.usesSnapped(t))
        assertEquals(t.polygons, SnapDisplay.polygonsFor(t))
    }

    @Test
    fun `a matched ring too small to be a ring is ignored`() {
        val t = territory(
            snappedRing = listOf(BERLIN, LatLng(BERLIN.lat, BERLIN.lng + SIDE_DEG)),
            snappedAtEpochMs = 5_000L,
        )

        assertFalse(SnapDisplay.usesSnapped(t))
        assertEquals(t.polygons, SnapDisplay.polygonsFor(t))
    }

    /** A walked claim is not a thing to render as nothing because a string disagreed. */
    @Test
    fun `empty geometry falls back to the as-walked ring`() {
        val ring = square(BERLIN, SIDE_DEG)
        val t = territory(polygons = emptyList())

        assertEquals(Territory.polygonsFromRing(ring), SnapDisplay.polygonsFor(t))
    }

    @Test
    fun `a claim with nothing at all draws nothing rather than throwing`() {
        val t = territory(ring = emptyList(), polygons = emptyList())

        assertTrue(SnapDisplay.polygonsFor(t).isEmpty())
        assertTrue(SnapDisplay.pointsFor(t).isEmpty())
    }

    @Test
    fun `camera points come from whichever outline is drawn`() {
        val snapped = square(SNAPPED_ORIGIN, SIDE_DEG)
        val t = territory(snappedRing = snapped, snappedAtEpochMs = 1_000L)

        assertEquals(snapped, SnapDisplay.pointsFor(t))
    }

    // --- helpers -------------------------------------------------------------

    private fun square(at: LatLng, sizeDeg: Double): List<LatLng> = listOf(
        at,
        LatLng(at.lat, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng),
    )

    private fun territory(
        ring: List<LatLng> = square(BERLIN, SIDE_DEG),
        polygons: List<io.app.enclose.geo.GeoPolygon> = Territory.polygonsFromRing(ring),
        snappedRing: List<LatLng> = emptyList(),
        snappedAtEpochMs: Long? = null,
    ) = Territory(
        id = "t",
        name = "Claim",
        ring = ring,
        polygons = polygons,
        areaSqMeters = Geo.polygonAreaSqMeters(ring),
        perimeterMeters = Geo.pathLengthMeters(ring),
        claimedAtEpochMs = 0L,
        snappedRing = snappedRing,
        snappedAtEpochMs = snappedAtEpochMs,
    )

    private companion object {
        val BERLIN = LatLng(52.5200, 13.4050)
        val SNAPPED_ORIGIN = LatLng(52.5201, 13.4051)
        const val SIDE_DEG = 0.003
    }
}
