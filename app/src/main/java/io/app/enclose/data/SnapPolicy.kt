package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.GeoClip
import io.app.enclose.geo.GeoRing
import io.app.enclose.geo.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Why a match was refused. Named so a rejection can be explained, not just counted. */
enum class SnapRejection {
    /** Not enough points to be a ring at all. */
    TOO_FEW_POINTS,

    /** Some of the matched route runs too far from where the walk actually went. */
    DEVIATES,

    /**
     * Some of the walk has nothing matched near it — the route stopped short, or
     * cut a chord across ground that was walked round.
     */
    INCOMPLETE,

    /** The enclosed area moved too far — a lobe was dropped, or one invented. */
    AREA_CHANGED,

    /** The ring crosses itself, usually where the matcher joined end to start. */
    SELF_INTERSECTING,
}

/** The outcome of judging one match. */
sealed interface SnapVerdict {
    /** Good enough to draw. [ring] is the geometry to store, implicitly closed. */
    data class Accepted(val ring: GeoRing) : SnapVerdict

    data class Rejected(val reason: SnapRejection) : SnapVerdict
}

/**
 * Decides whether a matched route is a better drawing of a walk than the walk
 * itself, or whether it is wrong and must never reach the screen.
 *
 * A map matcher is a remote guess. Fed a noisy urban loop it will occasionally
 * lock onto a parallel road, re-route through a GPS dropout and invent a detour,
 * or return a figure-of-eight where the loop closes. Any of those, drawn as
 * someone's territory, is worse than the honest wobble it replaced — and because
 * snapping is cosmetic, the safe answer is always available: keep the raw ring.
 *
 * So this is deliberately a **gate, not a repair**. It rejects rather than
 * fixing, because a half-corrected route is a shape nobody walked and nobody
 * chose.
 *
 * Pure, so all of it is unit tested — this is the only thing standing between a
 * bad remote answer and the map.
 */
object SnapPolicy {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * How far a matched vertex may sit from the walked path.
     *
     * 25 m is roughly a street's width plus a poor fix. Below that and ordinary,
     * correct matching gets rejected — a centreline is genuinely metres from the
     * pavement someone walked on, and a fix under trees is worse. Above it and a
     * lock onto the parallel road one block over starts to pass.
     */
    const val MAX_DEVIATION_METERS = 25.0

    /**
     * How much the enclosed area may move, as a fraction.
     *
     * Snapping legitimately shrinks a claim a little — GPS noise bulges a loop
     * outward and matching pulls it back to centrelines — so this is not
     * symmetrical about zero in spirit, but it is kept symmetrical in code
     * because a matcher that *grows* a claim by a fifth has invented ground just
     * as surely as one that drops a lobe has lost it.
     */
    const val MAX_AREA_DRIFT = 0.20

    /**
     * At most this many matched vertices are distance-checked.
     *
     * The check is every sampled vertex against every raw segment, so the cost is
     * a product. A long walk can be thousands of points on both sides, which is
     * millions of comparisons — and the lesson this repo already paid for with
     * `GpxImporter` is that a JVM unit test says nothing about what that costs on
     * ART. Sampling evenly across the route bounds the work without weakening the
     * check: a matcher wrong enough to matter is wrong for a stretch, not for one
     * isolated vertex.
     */
    const val MAX_SAMPLED_VERTICES = 400

    /**
     * Judge [matched] against the walk it claims to describe.
     *
     * [raw] is the as-walked ring and stays the authority throughout — nothing
     * here can change it, only decline to decorate it.
     */
    fun judge(raw: GeoRing, matched: List<LatLng>): SnapVerdict {
        if (raw.size < 3) return SnapVerdict.Rejected(SnapRejection.TOO_FEW_POINTS)

        // A matcher returns a path, so its end may repeat its start. Our rings
        // are implicitly closed, so that duplicate is dropped before anything
        // else looks at the shape.
        val ring = matched.dropClosingDuplicate()
        if (ring.size < 3) return SnapVerdict.Rejected(SnapRejection.TOO_FEW_POINTS)

        if (!GeoClip.isSimpleRing(ring)) {
            return SnapVerdict.Rejected(SnapRejection.SELF_INTERSECTING)
        }

        val rawArea = Geo.polygonAreaSqMeters(raw)
        val matchedArea = Geo.polygonAreaSqMeters(ring)
        if (!withinDrift(rawArea, matchedArea, MAX_AREA_DRIFT)) {
            return SnapVerdict.Rejected(SnapRejection.AREA_CHANGED)
        }

        // Both directions, and they catch different failures. Matched-to-raw
        // catches a route that wandered onto the wrong road; raw-to-matched
        // catches one that stopped short or cut a chord across the walk.
        if (maxDeviationMeters(from = ring, to = raw) > MAX_DEVIATION_METERS) {
            return SnapVerdict.Rejected(SnapRejection.DEVIATES)
        }
        if (maxDeviationMeters(from = raw, to = ring) > MAX_DEVIATION_METERS) {
            return SnapVerdict.Rejected(SnapRejection.INCOMPLETE)
        }

        return SnapVerdict.Accepted(ring)
    }

    /**
     * The furthest any sampled vertex of [from] sits from the outline [to] traces
     * — one direction of a sampled Hausdorff distance.
     *
     * Measured to *segments*, not to vertices: a straight 200 m stretch walked
     * with two fixes has nothing to be near in the middle, and comparing
     * vertex-to-vertex would reject a correctly straight match for being straight.
     * Both rings are treated as implicitly closed, so the segment from the last
     * point back to the first counts like any other.
     *
     * This replaced a closing-gap check — "reject if the match's end is far from
     * its start" — which cannot be made correct and should not be added back. A
     * ring here is *implicitly* closed, so its last-to-first distance is simply
     * the length of its closing edge, which is arbitrary; on a square it is a
     * whole side. And which vertex is "last" changes the moment a matcher
     * densifies an edge, so endpoint comparisons are meaningless too. Coverage in
     * this direction is what actually catches a route that never came back: the
     * stretch it skipped leaves raw vertices with nothing matched near them.
     */
    private fun maxDeviationMeters(from: GeoRing, to: GeoRing): Double {
        // One projection for both, around a shared reference latitude, so the
        // frames are comparable — the same scheme Geo and GeoClip use.
        val lat0 = Math.toRadians(to.map { it.lat }.average())
        val cosLat0 = cos(lat0)
        fun projectX(p: LatLng) = Math.toRadians(p.lng) * cosLat0 * EARTH_RADIUS_M
        fun projectY(p: LatLng) = Math.toRadians(p.lat) * EARTH_RADIUS_M

        // Closed, so the last segment runs back to the start.
        val toX = DoubleArray(to.size + 1)
        val toY = DoubleArray(to.size + 1)
        for (i in to.indices) {
            toX[i] = projectX(to[i])
            toY[i] = projectY(to[i])
        }
        toX[to.size] = toX[0]
        toY[to.size] = toY[0]

        val step = max(1, from.size / MAX_SAMPLED_VERTICES)
        var worst = 0.0
        var i = 0
        while (i < from.size) {
            val px = projectX(from[i])
            val py = projectY(from[i])
            var nearest = Double.MAX_VALUE
            for (s in 0 until to.size) {
                val d = pointToSegment(px, py, toX[s], toY[s], toX[s + 1], toY[s + 1])
                if (d < nearest) nearest = d
                if (nearest <= 0.0) break
            }
            if (nearest > worst) worst = nearest
            // Cheap exit: one vertex past tolerance is enough to reject.
            if (worst > MAX_DEVIATION_METERS) return worst
            i += step
        }
        return worst
    }

    private fun pointToSegment(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /** True when [actual] is within [drift] (a fraction) of [expected]. */
    private fun withinDrift(expected: Double, actual: Double, drift: Double): Boolean {
        // A degenerate walk has no area to compare against; the deviation check
        // still has to answer for it.
        if (expected <= 0.0) return true
        return abs(actual - expected) / expected <= drift
    }

    /** Rings here are implicitly closed, so a repeated final point is dropped. */
    private fun List<LatLng>.dropClosingDuplicate(): List<LatLng> =
        if (size > 1 && first() == last()) dropLast(1) else this
}
