package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thinning before a route goes over the wire. The property that matters is not
 * "fewer points" — it is that nothing further than the tolerance from the line it
 * sits on is ever removed, because a cut corner gets matched to the wrong road
 * and comes back as a plausible loop nobody walked.
 */
class RouteSimplifyTest {

    @Test
    fun `collinear points are removed`() {
        val straight = (0..10).map { LatLng(52.5, 13.4 + it * 0.001) }

        val simplified = RouteSimplify.simplify(straight, toleranceMeters = 3.0)

        assertEquals(2, simplified.size)
        assertEquals(straight.first(), simplified.first())
        assertEquals(straight.last(), simplified.last())
    }

    /** The corner is the point of the walk; thinning must never take it. */
    @Test
    fun `a real corner survives`() {
        val corner = listOf(
            LatLng(52.5000, 13.4000),
            LatLng(52.5000, 13.4010),
            LatLng(52.5000, 13.4020),
            LatLng(52.5010, 13.4020),
            LatLng(52.5020, 13.4020),
        )

        val simplified = RouteSimplify.simplify(corner, toleranceMeters = 3.0)

        assertEquals(3, simplified.size)
        assertTrue(simplified.contains(LatLng(52.5000, 13.4020)))
    }

    /**
     * The guarantee, stated directly: every dropped point was within tolerance of
     * the simplified line. Checked over a noisy path rather than a tidy fixture.
     */
    @Test
    fun `no point is ever further from the result than the tolerance`() {
        val tolerance = 5.0
        val noisy = (0 until 300).map { i ->
            val wobble = if (i % 3 == 0) 0.00002 else -0.00001
            LatLng(52.5 + i * 0.00005 + wobble, 13.4 + i * 0.00008)
        }

        val simplified = RouteSimplify.simplify(noisy, tolerance)

        assertTrue(simplified.size < noisy.size)
        // The code measures in its equirectangular metre frame; this measures with
        // Geo's haversine. The two agree to well under a percent at city scale, so
        // the slack is for the models disagreeing, not for the guarantee bending.
        val slack = tolerance * 0.01
        for (p in noisy) {
            val d = distanceToPath(p, simplified)
            assertTrue(
                "dropped a point ${"%.3f".format(d)} m from the simplified line, tolerance $tolerance",
                d <= tolerance + slack,
            )
        }
    }

    @Test
    fun `paths too short to thin come back untouched`() {
        val two = listOf(LatLng(52.5, 13.4), LatLng(52.6, 13.5))

        assertSame(two, RouteSimplify.simplify(two))
        assertSame(two, RouteSimplify.simplify(two, toleranceMeters = 1_000.0))
        assertTrue(RouteSimplify.simplify(emptyList()).isEmpty())
    }

    @Test
    fun `a zero tolerance changes nothing`() {
        val path = (0..10).map { LatLng(52.5, 13.4 + it * 0.001) }

        assertSame(path, RouteSimplify.simplify(path, toleranceMeters = 0.0))
    }

    /**
     * A ring has no natural first or last point, so the closing segment has to be
     * thinned like every other one — otherwise the one place a loop's noise
     * survives is exactly where the loop is most visible.
     */
    @Test
    fun `a ring thins its closing segment too`() {
        // A square whose closing edge is padded with collinear filler.
        val ring = listOf(
            LatLng(52.5000, 13.4000),
            LatLng(52.5000, 13.4030),
            LatLng(52.5030, 13.4030),
            LatLng(52.5030, 13.4000),
            LatLng(52.5020, 13.4000),
            LatLng(52.5010, 13.4000),
        )

        val simplified = RouteSimplify.simplifyRing(ring, toleranceMeters = 3.0)

        assertEquals(4, simplified.size)
        // Still implicitly closed — no duplicate of the start was left behind.
        assertTrue(simplified.first() != simplified.last())
    }

    /** An area of zero is not a tidier walk; it is the loss of one. */
    @Test
    fun `a ring is never thinned below a triangle`() {
        val sliver = listOf(
            LatLng(52.5000, 13.4000),
            LatLng(52.5000, 13.4010),
            LatLng(52.5000, 13.4020),
            LatLng(52.5000, 13.4030),
        )

        val simplified = RouteSimplify.simplifyRing(sliver, toleranceMeters = 50.0)

        assertSame(sliver, simplified)
    }

    @Test
    fun `a triangle is already minimal`() {
        val triangle = listOf(
            LatLng(52.5000, 13.4000),
            LatLng(52.5000, 13.4030),
            LatLng(52.5030, 13.4030),
        )

        assertSame(triangle, RouteSimplify.simplifyRing(triangle))
    }

    /**
     * Iterative, not recursive: a long path with a pathological shape is a stack
     * overflow on a device that a JVM with a bigger stack would never show.
     */
    @Test
    fun `a very long path does not overflow the stack`() {
        val long = (0 until 50_000).map { LatLng(52.5 + it * 1e-7, 13.4 + it * 1e-7) }

        val simplified = RouteSimplify.simplify(long, toleranceMeters = 0.5)

        assertTrue(simplified.size >= 2)
    }

    // --- helpers -------------------------------------------------------------

    private fun distanceToPath(point: LatLng, path: List<LatLng>): Double {
        var nearest = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            nearest = minOf(nearest, distanceToSegment(point, path[i], path[i + 1]))
        }
        return nearest
    }

    /**
     * Independent of the implementation: measures with `Geo`'s own haversine and
     * borrows none of the code under test's projection.
     *
     * Ternary search rather than sampling the segment at fixed steps. Distance to
     * a point is convex along the segment, so this converges to the true minimum —
     * whereas fixed sampling overestimates by up to half the step, and a
     * simplified segment can be kilometres long, which is enough to fail a 5 m
     * assertion for no reason but the measurement.
     */
    private fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        if (a == b) return Geo.distanceMeters(p, a)
        fun at(t: Double) = Geo.distanceMeters(
            p,
            LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t),
        )
        var lo = 0.0
        var hi = 1.0
        repeat(200) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            if (at(m1) < at(m2)) hi = m2 else lo = m1
        }
        return at((lo + hi) / 2)
    }
}
