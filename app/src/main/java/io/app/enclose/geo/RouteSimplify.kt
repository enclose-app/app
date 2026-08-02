package io.app.enclose.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Douglas-Peucker thinning, used to shrink a walked ring before it goes over the
 * wire to a map matcher.
 *
 * **The tolerance is in metres, and it is never a target point count.** That
 * distinction is the whole reason this has its own file and its own tests. Thin
 * to "at most N points" and a long walk gets a tolerance large enough to cut a
 * corner off; the matcher then snaps the shortened corner to *the wrong road*,
 * and what comes back is a plausible loop of roughly the right area that no
 * downstream check can tell from a correct one. A fixed metre tolerance can only
 * ever remove points that were already within that distance of the line they sit
 * on, which is exactly the GPS noise a matcher is about to discard anyway.
 *
 * [DEFAULT_TOLERANCE_METERS] is deliberately smaller than
 * [io.app.enclose.tracking.TrackingManager]'s 4 m jitter gate: this must not be
 * the thing that decides the shape of a walk.
 */
object RouteSimplify {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * 3 m — under the accuracy of a good urban fix, so a removed point was never
     * evidence of a turn, and well under the width of the roads being matched to.
     */
    const val DEFAULT_TOLERANCE_METERS = 3.0

    /**
     * [points] with everything that sits within [toleranceMeters] of the line it
     * lies on removed. The first and last points are always kept.
     *
     * Iterative rather than recursive: a walk is thousands of points and a
     * pathological one is deep, so recursion here is a stack overflow on a
     * device that a unit test on a JVM with a bigger stack would never show.
     */
    fun simplify(
        points: List<LatLng>,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
    ): List<LatLng> {
        if (points.size <= 2 || toleranceMeters <= 0.0) return points

        // Project once, up front, into a local metre frame — the same
        // equirectangular scheme Geo and GeoClip use. Doing the trigonometry per
        // distance check instead would dominate the run.
        val lat0 = Math.toRadians(points.map { it.lat }.average())
        val cosLat0 = cos(lat0)
        val xs = DoubleArray(points.size)
        val ys = DoubleArray(points.size)
        for (i in points.indices) {
            xs[i] = Math.toRadians(points[i].lng) * cosLat0 * EARTH_RADIUS_M
            ys[i] = Math.toRadians(points[i].lat) * EARTH_RADIUS_M
        }

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue

            var farthest = -1
            var farthestDistance = toleranceMeters
            for (i in first + 1 until last) {
                val d = perpendicularDistance(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
                if (d > farthestDistance) {
                    farthest = i
                    farthestDistance = d
                }
            }
            if (farthest < 0) continue

            keep[farthest] = true
            stack.addLast(first to farthest)
            stack.addLast(farthest to last)
        }

        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The same thinning for a ring, which has no natural first or last point.
     *
     * A ring is handed to [simplify] with its start repeated at the end, so the
     * segment that closes the loop is thinned like every other one, and the
     * duplicate is then dropped — rings in this app are implicitly closed (see
     * [Geo]). Without that, the closing segment is the one stretch of a walk
     * whose noise survives, which is visible precisely where the loop meets.
     */
    fun simplifyRing(
        ring: List<LatLng>,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
    ): List<LatLng> {
        if (ring.size <= 3) return ring
        val closed = ring + ring.first()
        val simplified = simplify(closed, toleranceMeters)
        val open = simplified.dropLast(1)
        // Never thin a ring below a triangle: an area of zero is not a tidier
        // version of a walk, it is the loss of one.
        return if (open.size >= 3) open else ring
    }

    /** Distance from (px,py) to the segment (ax,ay)-(bx,by), all in metres. */
    private fun perpendicularDistance(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return hypot(px - ax, py - ay)
        // Project onto the segment and clamp, so a point beyond either end
        // measures to the end rather than to the infinite line.
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun hypot(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy).let { abs(it) }
}
