package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only thing standing between a bad remote answer and someone's map.
 *
 * Each rejection here corresponds to a way real map matchers fail: locking onto
 * the parallel road, re-routing through a GPS dropout, and — most often — making
 * a mess of the seam where a loop's end meets its start.
 */
class SnapPolicyTest {

    @Test
    fun `a match that follows the walk is accepted`() {
        val raw = square(SIDE_DEG)
        // Nudged a few metres, as a centreline sits from the pavement walked on.
        val matched = raw.map { LatLng(it.lat + 0.00005, it.lng + 0.00005) }

        val verdict = SnapPolicy.judge(raw, matched)

        assertTrue(verdict is SnapVerdict.Accepted)
        assertEquals(4, (verdict as SnapVerdict.Accepted).ring.size)
    }

    /**
     * A matcher returns a path, so it may repeat the start at the end. Rings here
     * are implicitly closed, so that duplicate must be dropped on the way in
     * rather than stored and drawn as a zero-length segment.
     */
    @Test
    fun `a repeated closing point is dropped, not rejected`() {
        val raw = square(SIDE_DEG)
        val matched = raw + raw.first()

        val verdict = SnapPolicy.judge(raw, matched)

        assertTrue(verdict is SnapVerdict.Accepted)
        val ring = (verdict as SnapVerdict.Accepted).ring
        assertEquals(4, ring.size)
        assertTrue(ring.first() != ring.last())
    }

    @Test
    fun `too few points is rejected`() {
        val raw = square(SIDE_DEG)

        assertRejected(SnapRejection.TOO_FEW_POINTS, SnapPolicy.judge(raw, emptyList()))
        assertRejected(SnapRejection.TOO_FEW_POINTS, SnapPolicy.judge(raw, raw.take(2)))
    }

    @Test
    fun `a raw ring that is not a ring is rejected`() {
        val two = listOf(LatLng(52.5, 13.4), LatLng(52.6, 13.5))

        assertRejected(SnapRejection.TOO_FEW_POINTS, SnapPolicy.judge(two, square(SIDE_DEG)))
    }

    /**
     * The commonest real failure: the matcher stops part way and the ring closes
     * with a chord across ground that was actually walked round.
     *
     * Note what this is *not* checked by. A ring here is implicitly closed, so
     * "the end is far from the start" is meaningless — on a square that distance
     * is a whole side. What gives it away is that the skipped stretch of the walk
     * has nothing matched anywhere near it.
     */
    @Test
    fun `a route that skips part of the walk is rejected as incomplete`() {
        // A square with a dead-end spur — someone walked up a cul-de-sac and back.
        // An out-and-back adds essentially no *area*, which is exactly why this
        // needs its own check: drop the spur and the area gate notices nothing.
        val spurTip = LatLng(BERLIN.lat - SIDE_DEG * 0.4, BERLIN.lng + SIDE_DEG / 2)
        val spurBase = LatLng(BERLIN.lat, BERLIN.lng + SIDE_DEG / 2)
        val raw = listOf(
            BERLIN,
            spurBase,
            spurTip,
            spurBase,
            LatLng(BERLIN.lat, BERLIN.lng + SIDE_DEG),
            LatLng(BERLIN.lat + SIDE_DEG, BERLIN.lng + SIDE_DEG),
            LatLng(BERLIN.lat + SIDE_DEG, BERLIN.lng),
        )
        // The matcher returned a tidy square and quietly lost the cul-de-sac.
        val matched = square(SIDE_DEG)

        assertTrue(
            "the fixture must not be catchable on area alone",
            abs(Geo.polygonAreaSqMeters(matched) - Geo.polygonAreaSqMeters(raw)) /
                Geo.polygonAreaSqMeters(raw) < SnapPolicy.MAX_AREA_DRIFT,
        )
        assertRejected(SnapRejection.INCOMPLETE, SnapPolicy.judge(raw, matched))
    }

    /**
     * A figure-of-eight has a plausible area and a plausible perimeter, so only a
     * topology check catches it — and JTS would compute nonsense against it later.
     */
    @Test
    fun `a self-crossing ring is rejected`() {
        val raw = square(SIDE_DEG)
        // The classic bowtie: two corners swapped.
        val matched = listOf(raw[0], raw[1], raw[3], raw[2])

        assertRejected(SnapRejection.SELF_INTERSECTING, SnapPolicy.judge(raw, matched))
    }

    @Test
    fun `a match that drops a lobe is rejected on area`() {
        val raw = square(SIDE_DEG)
        // Half the square: closes, is simple, but is not the walk.
        val matched = listOf(
            raw[0],
            LatLng(raw[0].lat, raw[0].lng + SIDE_DEG / 2),
            LatLng(raw[3].lat, raw[3].lng + SIDE_DEG / 2),
            raw[3],
        )

        assertRejected(SnapRejection.AREA_CHANGED, SnapPolicy.judge(raw, matched))
    }

    /**
     * Locking onto the road one block over. The shape is the right size and the
     * right topology — only its position gives it away.
     */
    @Test
    fun `a match on the parallel road is rejected as deviating`() {
        val raw = square(SIDE_DEG)
        // ~110 m north, well past MAX_DEVIATION_METERS but the same area.
        val matched = raw.map { LatLng(it.lat + 0.001, it.lng) }

        assertRejected(SnapRejection.DEVIATES, SnapPolicy.judge(raw, matched))
    }

    /**
     * A straight stretch walked with two fixes has nothing to be near in the
     * middle. Measuring to raw *segments* rather than raw vertices is what stops
     * a correctly straight match being rejected for it.
     */
    @Test
    fun `a densified straight stretch is not treated as deviation`() {
        val raw = square(SIDE_DEG)
        // Matchers return far more vertices than were walked.
        val matched = densify(raw, every = 20)

        val verdict = SnapPolicy.judge(raw, matched)

        assertTrue("densifying a straight edge must not read as drift", verdict is SnapVerdict.Accepted)
    }

    /** Sampling must not let a long match skip the check entirely. */
    @Test
    fun `a long match that wanders is still caught`() {
        val raw = square(SIDE_DEG)
        val matched = densify(raw, every = 500).map { LatLng(it.lat + 0.001, it.lng) }

        assertRejected(SnapRejection.DEVIATES, SnapPolicy.judge(raw, matched))
    }

    @Test
    fun `the area gate is symmetrical`() {
        val raw = square(SIDE_DEG)
        val rawArea = Geo.polygonAreaSqMeters(raw)
        // Grown well past the drift allowance, centred so it doesn't also deviate.
        val grown = square(SIDE_DEG * 1.4)
        val shifted = grown.map {
            LatLng(it.lat - SIDE_DEG * 0.2, it.lng - SIDE_DEG * 0.2)
        }

        assertTrue(Geo.polygonAreaSqMeters(shifted) > rawArea * (1 + SnapPolicy.MAX_AREA_DRIFT))
        val verdict = SnapPolicy.judge(raw, shifted)
        assertTrue(verdict is SnapVerdict.Rejected)
    }

    // --- helpers -------------------------------------------------------------

    private fun assertRejected(expected: SnapRejection, verdict: SnapVerdict) {
        assertTrue("expected a rejection, got $verdict", verdict is SnapVerdict.Rejected)
        assertEquals(expected, (verdict as SnapVerdict.Rejected).reason)
    }

    /** An axis-aligned square, matching the fixture idiom in ConquestTest. */
    private fun square(sizeDeg: Double): List<LatLng> = listOf(
        BERLIN,
        LatLng(BERLIN.lat, BERLIN.lng + sizeDeg),
        LatLng(BERLIN.lat + sizeDeg, BERLIN.lng + sizeDeg),
        LatLng(BERLIN.lat + sizeDeg, BERLIN.lng),
    )

    /** Insert [every] evenly spaced points along each edge, closing the ring. */
    private fun densify(ring: List<LatLng>, every: Int): List<LatLng> {
        val out = mutableListOf<LatLng>()
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            for (s in 0 until every) {
                val t = s.toDouble() / every
                out.add(LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
            }
        }
        return out
    }

    private companion object {
        val BERLIN = LatLng(52.5200, 13.4050)

        /** ~330 m on a side — a small city block loop. */
        const val SIDE_DEG = 0.003
    }
}
