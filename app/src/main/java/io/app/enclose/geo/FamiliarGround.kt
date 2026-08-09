package io.app.enclose.geo

import kotlin.math.cos
import kotlin.math.floor

/**
 * Ground the walker has already covered, as something a route search can be
 * pulled towards.
 *
 * A suggestion is much more likely to be taken up if it runs along streets the
 * walker already knows — and in this app there is a second reason, which is that
 * a loop overlapping an existing claim is how a claim gets *re-walked* and
 * defended rather than abandoned. So the planner is given the outlines of what
 * has already been claimed and charges less for edges that sit on them.
 *
 * A discount rather than a requirement, deliberately. Insisting on familiar
 * ground would mean somebody with one claim could only ever be offered that one
 * loop, and somebody with none could be offered nothing at all. Cheaper, not
 * mandatory, is what makes the shuffle produce "the same walk again", "that walk
 * with a different half", and "somewhere new" from the same search.
 */
class FamiliarGround private constructor(
    private val grid: Map<Long, DoubleArray>,
    private val cosLat0: Double,
    private val radiusMeters: Double,
    private val discount: Double,
) {

    /** True when there is nothing to be pulled towards. */
    val isEmpty: Boolean get() = grid.isEmpty()

    /**
     * The cost multiplier for walking at [point]: [discount] on familiar ground,
     * 1.0 everywhere else.
     */
    fun factorAt(point: LatLng): Double {
        if (grid.isEmpty()) return 1.0
        val x = Math.toRadians(point.lng) * cosLat0 * EARTH_RADIUS_M
        val y = Math.toRadians(point.lat) * EARTH_RADIUS_M
        val cellX = floor(x / radiusMeters).toInt()
        val cellY = floor(y / radiusMeters).toInt()
        val limit = radiusMeters * radiusMeters
        for (gy in (cellY - 1)..(cellY + 1)) {
            for (gx in (cellX - 1)..(cellX + 1)) {
                val cell = grid[key(gx, gy)] ?: continue
                var i = 0
                while (i < cell.size) {
                    val dx = cell[i] - x
                    val dy = cell[i + 1] - y
                    if (dx * dx + dy * dy <= limit) return discount
                    i += 2
                }
            }
        }
        return 1.0
    }

    /**
     * How much of [route] runs over ground already covered, by length — what a
     * suggestion needs to be able to say "mostly along claims you already hold"
     * rather than leaving the user to compare two lines on a map.
     *
     * Measured per segment on the segment's own midpoint, so a long edge across
     * unfamiliar ground can't be counted as familiar because it happened to
     * start on a claimed street.
     */
    fun familiarFraction(route: List<LatLng>): Double {
        if (isEmpty || route.size < 2) return 0.0
        var total = 0.0
        var familiar = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val length = Geo.distanceMeters(a, b)
            total += length
            val middle = LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2)
            if (factorAt(middle) < 1.0) familiar += length
        }
        return if (total <= 0) 0.0 else familiar / total
    }

    companion object {

        /** Nothing walked yet, or nothing near enough to matter. */
        val NONE = FamiliarGround(emptyMap(), 1.0, 1.0, 1.0)

        /**
         * How close counts as "the same street". A claim's ring is a GPS trace
         * down one side of a road, and the mapped centreline it should match is
         * half a carriageway away — plus whatever the fix was out by.
         */
        const val NEAR_METERS = 45.0

        /**
         * What familiar ground costs instead. Two thirds: enough that the search
         * will take a noticeable detour to rejoin a known route, not so much
         * that it will walk twice as far to avoid a new street.
         */
        const val DISCOUNT = 0.66

        /**
         * Build from [routes] — the rings of claims worth revisiting.
         *
         * Points are never thinned, and long stretches between them are
         * **filled in**. A GPS trace has a point every few metres and needs
         * neither, but a route that has been through a simplifier, or one
         * recorded down a straight road where nothing changed for 200 m, arrives
         * as two distant points with nothing between them — and a search asking
         * "is the middle of this street familiar?" would be told no about a
         * street the walker walks every day.
         */
        fun of(
            routes: List<List<LatLng>>,
            nearMeters: Double = NEAR_METERS,
            discount: Double = DISCOUNT,
        ): FamiliarGround {
            val points = routes.flatMap { densify(it, nearMeters / 2) }
            if (points.isEmpty()) return NONE
            val cosLat0 = cos(Math.toRadians(points.sumOf { it.lat } / points.size))
            val cells = HashMap<Long, MutableList<Double>>()
            for (point in points) {
                val x = Math.toRadians(point.lng) * cosLat0 * EARTH_RADIUS_M
                val y = Math.toRadians(point.lat) * EARTH_RADIUS_M
                val cell = cells.getOrPut(
                    key(floor(x / nearMeters).toInt(), floor(y / nearMeters).toInt()),
                ) { ArrayList() }
                cell.add(x)
                cell.add(y)
            }
            return FamiliarGround(
                grid = cells.mapValues { (_, list) -> list.toDoubleArray() },
                cosLat0 = cosLat0,
                radiusMeters = nearMeters,
                discount = discount,
            )
        }

        /**
         * [route] with intermediate points inserted so no two are further than
         * [spacingMeters] apart.
         *
         * The subdivision of any one segment is capped: a route with a wild
         * point in it (a GPS fix that landed in another country) must not be
         * able to fill memory with a million interpolated points.
         */
        private fun densify(route: List<LatLng>, spacingMeters: Double): List<LatLng> {
            if (route.size < 2) return route
            val out = ArrayList<LatLng>(route.size)
            for (i in 1 until route.size) {
                val a = route[i - 1]
                val b = route[i]
                out.add(a)
                val steps = (Geo.distanceMeters(a, b) / spacingMeters).toInt()
                    .coerceAtMost(MAX_SUBDIVISIONS)
                for (step in 1 until steps) {
                    val t = step.toDouble() / steps
                    out.add(LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                }
            }
            out.add(route.last())
            return out
        }

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

        /** Enough to fill a kilometre at the spacing this uses. */
        private const val MAX_SUBDIVISIONS = 64

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
