package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place that decides which outline a claim is drawn with. The case that
 * matters most is the carved one: a matched outline describes the whole loop as
 * walked, so drawing it after a rival has taken part of that loop would show
 * ground the user no longer owns — permanently, since `Conquest` only revisits a
 * claim when a new walk overlaps it.
 */
class SnapDisplayTest {

    @Test
    fun `an unmatched claim draws its own geometry`() {
        val t = territory()

        assertFalse(SnapDisplay.usesSnapped(t))
        assertEquals(t.polygons, SnapDisplay.polygonsFor(t))
    }

    @Test
    fun `a matched, uncarved claim draws the matched outline`() {
        val snapped = square(SNAPPED_ORIGIN, SIDE_DEG)
        val t = territory(snappedRing = snapped, snappedAtEpochMs = 1_000L)

        assertTrue(SnapDisplay.usesSnapped(t))
        assertEquals(Territory.polygonsFromRing(snapped), SnapDisplay.polygonsFor(t))
    }

    /** The whole reason the carve timestamp exists. */
    @Test
    fun `a carved claim goes back to its carved geometry`() {
        val snapped = square(SNAPPED_ORIGIN, SIDE_DEG)
        val carvedGeometry = Territory.polygonsFromRing(square(BERLIN, SIDE_DEG / 2))
        val t = territory(
            polygons = carvedGeometry,
            snappedRing = snapped,
            snappedAtEpochMs = 1_000L,
            carvedAtEpochMs = 2_000L,
        )

        assertFalse(SnapDisplay.usesSnapped(t))
        assertEquals(carvedGeometry, SnapDisplay.polygonsFor(t))
    }

    /**
     * Order doesn't matter: a claim carved *before* it was ever matched must
     * behave the same as one carved after. This is the sequence that actually
     * happens — matching is opt-in and needs a network, so a backfill can arrive
     * long after the carve.
     */
    @Test
    fun `matching after a carve still does not draw the matched outline`() {
        val t = territory(
            polygons = Territory.polygonsFromRing(square(BERLIN, SIDE_DEG / 2)),
            snappedRing = square(SNAPPED_ORIGIN, SIDE_DEG),
            snappedAtEpochMs = 9_000L,
            carvedAtEpochMs = 2_000L,
        )

        assertFalse(SnapDisplay.usesSnapped(t))
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
        carvedAtEpochMs: Long? = null,
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
        carvedAtEpochMs = carvedAtEpochMs,
    )

    private companion object {
        val BERLIN = LatLng(52.5200, 13.4050)
        val SNAPPED_ORIGIN = LatLng(52.5201, 13.4051)
        const val SIDE_DEG = 0.003
    }
}
