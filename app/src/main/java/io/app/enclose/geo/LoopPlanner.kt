package io.app.enclose.geo

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs

/**
 * Finds a walking loop of roughly the length you asked for, starting and
 * finishing where you're standing.
 *
 * ## The shape of the search
 *
 * Asking for "a closed walk of 5 km" directly is a hard problem — it is the
 * travelling salesman's awkward cousin, and the honest algorithms for it are far
 * too slow to run on a phone while somebody waits. What works, and what this
 * does, is to turn it into two ordinary shortest-path searches:
 *
 *  1. Pick a direction, and a point out that way.
 *  2. Shortest path to it.
 *  3. Shortest path **back**, with every edge just used made expensive.
 *
 * Step 3 is what makes it a loop rather than a there-and-back: the way home is
 * free to reuse a street when there is genuinely no alternative (a dead-end
 * lane, the only bridge), but pays [RETRACE_PENALTY] times over for it, so it
 * takes the next street round wherever one exists.
 *
 * How far out to aim isn't known in advance — it depends entirely on how the
 * streets happen to run — so the radius is *refined*: plan, measure, scale the
 * radius by how far off it was, plan again. Three or four passes lands inside
 * the tolerance almost always, and the best near-miss is kept in case none of
 * them do.
 *
 * ## Shuffling
 *
 * [seed] fixes the direction the loop sets off in, and successive seeds are
 * spread around the compass by the golden angle — so pressing the button again
 * gives a route that goes somewhere visibly different, rather than the same
 * streets in a slightly different order. The whole search is deterministic: the
 * same seed over the same tiles gives the same loop, which is what lets a
 * suggestion survive a screen rotation without quietly becoming another one.
 *
 * Pure Kotlin over a [PathGraph]: no Android, no network, no clock — so the
 * awkward parts (does it come back? is it the right length? does it retrace?)
 * are unit tested rather than walked.
 */
object LoopPlanner {

    /** A closed route, ready to draw and to walk. */
    data class Loop(
        /** Starts and ends at the walker's own position. */
        val points: List<LatLng>,
        val lengthMeters: Double,
        /** How much of it is walked twice — see [RETRACE_PENALTY]. */
        val retracedMeters: Double,
    ) {
        /** The fraction of the loop that doubles back on itself. */
        val retracedFraction: Double
            get() = if (lengthMeters <= 0) 0.0 else retracedMeters / lengthMeters
    }

    /**
     * A loop of about [targetMeters] from [start], or null when there isn't one.
     *
     * Null is a legitimate and common answer — no mapped paths near the start, a
     * target too long for the tiles that were fetched, or a street layout with
     * no way round — and the caller is expected to say so rather than to retry
     * forever.
     */
    fun plan(
        graph: PathGraph,
        start: LatLng,
        targetMeters: Double,
        seed: Int,
        familiar: FamiliarGround = FamiliarGround.NONE,
    ): Loop? {
        if (targetMeters <= 0 || graph.nodeCount == 0) return null
        val startNode = graph.nearestNode(start) ?: return null

        val comfort = edgeCosts(graph, familiar)
        val search = Search(graph, comfort)
        var best: Loop? = null

        for (attempt in 0 until BEARINGS_PER_PLAN) {
            val bearing = bearingFor(seed, attempt)
            var radius = targetMeters / RADIUS_DIVISOR
            for (pass in 0 until REFINEMENTS) {
                val aim = Geo.destination(start, bearing, radius)
                // Half the radius of slack: further than that and the loop is no
                // longer going where this attempt meant it to go, which is the
                // one thing distinguishing this attempt from the last.
                val waypoint = graph.nearestNode(aim, (radius * WAYPOINT_SLACK).coerceAtLeast(60.0))
                    ?: break
                if (waypoint == startNode) break

                val loop = loopVia(graph, search, start, startNode, waypoint) ?: break
                if (loop.retracedFraction <= MAX_RETRACED) {
                    if (best == null || closer(loop, best, targetMeters)) best = loop
                    if (abs(loop.lengthMeters - targetMeters) <= targetMeters * TOLERANCE) {
                        return loop
                    }
                }
                // Scale towards the target, clamped so one wild result can't send
                // the next pass across the county or collapse it onto the start.
                val scale = (targetMeters / loop.lengthMeters).coerceIn(0.4, 2.5)
                radius *= scale
                if (radius < MIN_RADIUS_METERS) break
            }
        }

        // A near miss beats nothing: someone who asked for 5 km and is offered
        // 5.8 km can decide for themselves, whereas "no route found" when one
        // plainly exists reads as the feature being broken.
        return best?.takeIf { abs(it.lengthMeters - targetMeters) <= targetMeters * LOOSE_TOLERANCE }
    }

    /**
     * Out to [waypoint] and back a different way, as one polyline that begins
     * and ends at the walker's own position rather than at the junction the
     * search actually used — those last few metres are walked too, and a route
     * drawn from a street corner instead of from the walker looks like it starts
     * somewhere else.
     */
    private fun loopVia(
        graph: PathGraph,
        search: Search,
        start: LatLng,
        startNode: Int,
        waypoint: Int,
    ): Loop? {
        val outbound = search.path(startNode, waypoint, emptySet()) ?: return null
        val outboundEdges = outbound.toHashSet()
        val inbound = search.path(waypoint, startNode, outboundEdges) ?: return null

        val points = ArrayList<LatLng>()
        points.add(start)
        var at = startNode
        for (edge in outbound) {
            appendEdge(points, graph.edges[edge], at)
            at = graph.edges[edge].other(at)
        }
        for (edge in inbound) {
            appendEdge(points, graph.edges[edge], at)
            at = graph.edges[edge].other(at)
        }
        points.add(start)

        val retraced = inbound.filter { it in outboundEdges }.sumOf { graph.edges[it].lengthMeters }
        return Loop(
            points = points,
            lengthMeters = Geo.pathLengthMeters(points),
            retracedMeters = retraced,
        )
    }

    /**
     * Add an edge's geometry, dropping the vertex it shares with what's already
     * there. A repeated point is a zero-length segment, and those are a division
     * by zero waiting to happen in anything that takes a bearing along one.
     */
    private fun appendEdge(into: ArrayList<LatLng>, edge: PathGraph.Edge, from: Int) {
        val piece = edge.pointsFrom(from)
        val skipFirst = into.isNotEmpty() && into.last() == piece.first()
        into.addAll(if (skipFirst) piece.subList(1, piece.size) else piece)
    }

    /**
     * The cost of walking each edge: its length, weighted by how pleasant it is
     * and discounted where the walker has been before.
     *
     * Computed once per plan rather than inside the search, which relaxes each
     * edge many times over a dozen searches.
     */
    private fun edgeCosts(graph: PathGraph, familiar: FamiliarGround): DoubleArray =
        DoubleArray(graph.edges.size) { index ->
            val edge = graph.edges[index]
            val factor = if (familiar.isEmpty) 1.0 else familiar.factorAt(midpointOf(edge))
            edge.lengthMeters * edge.comfort * factor
        }

    /** The middle of an edge's own geometry, not of the line between its ends. */
    private fun midpointOf(edge: PathGraph.Edge): LatLng = edge.points[edge.points.size / 2]

    /** Which of two loops is nearer the asked-for length. */
    private fun closer(candidate: Loop, incumbent: Loop, target: Double): Boolean =
        abs(candidate.lengthMeters - target) < abs(incumbent.lengthMeters - target)

    /**
     * The direction attempt number [attempt] of seed [seed] sets off in.
     *
     * Successive values are a golden angle apart (≈137.5°), which is the
     * standard way to spread a sequence of directions so that no two nearby
     * numbers point the same way — pressing shuffle three times gives three
     * different parts of town rather than three variations on north.
     */
    private fun bearingFor(seed: Int, attempt: Int): Double {
        val index = seed * BEARINGS_PER_PLAN + attempt
        return (index * GOLDEN_ANGLE) % (2 * PI)
    }

    /** Dijkstra over the junction graph, with an optional set of edges to avoid. */
    private class Search(private val graph: PathGraph, private val cost: DoubleArray) {

        private val distance = DoubleArray(graph.nodeCount)
        private val cameBy = IntArray(graph.nodeCount)
        private val settled = BooleanArray(graph.nodeCount)

        /**
         * The cheapest run of edges from [from] to [to], or null when nothing
         * connects them. Edges in [penalised] are charged [RETRACE_PENALTY]
         * times over rather than banned — see the class docs.
         */
        fun path(from: Int, to: Int, penalised: Set<Int>): List<Int>? {
            java.util.Arrays.fill(distance, Double.MAX_VALUE)
            java.util.Arrays.fill(cameBy, -1)
            java.util.Arrays.fill(settled, false)
            distance[from] = 0.0

            val queue = PriorityQueue<Step>(64, compareBy { it.cost })
            queue.add(Step(from, 0.0))
            while (queue.isNotEmpty()) {
                val step = queue.poll() ?: break
                if (settled[step.node]) continue
                settled[step.node] = true
                if (step.node == to) return reconstruct(from, to)

                for (index in graph.edgesAt(step.node)) {
                    val edge = graph.edges[index]
                    val next = edge.other(step.node)
                    if (settled[next] || next == step.node) continue
                    val weight = if (index in penalised) {
                        cost[index] * RETRACE_PENALTY
                    } else {
                        cost[index]
                    }
                    val candidate = step.cost + weight
                    if (candidate < distance[next]) {
                        distance[next] = candidate
                        cameBy[next] = index
                        queue.add(Step(next, candidate))
                    }
                }
            }
            return null
        }

        private fun reconstruct(from: Int, to: Int): List<Int> {
            val path = ArrayList<Int>()
            var node = to
            while (node != from) {
                val index = cameBy[node]
                if (index < 0) return emptyList()
                path.add(index)
                node = graph.edges[index].other(node)
            }
            path.reverse()
            return path
        }

        private class Step(val node: Int, val cost: Double)
    }

    /**
     * What the way home pays for reusing a street it has already walked.
     *
     * Eight, from both ends: low enough that a genuine dead end (a lane with one
     * way in, a bridge that is the only crossing) is still taken rather than the
     * whole loop being abandoned, and high enough that the return leg will go
     * most of the way round a block to avoid retracing when it can.
     */
    const val RETRACE_PENALTY = 8.0

    /**
     * How much of a loop may be walked twice before it isn't one.
     *
     * A third. Past that it reads as a there-and-back, encloses next to nothing —
     * which in this app means it would claim next to nothing — and is not what
     * someone asking for a five-kilometre loop pictured.
     */
    const val MAX_RETRACED = 0.33

    /** Accepted straight away: within an eighth of the asked-for distance. */
    const val TOLERANCE = 0.12

    /** Offered as a near miss when nothing better turned up. */
    const val LOOSE_TOLERANCE = 0.3

    /**
     * The first guess at how far out to aim, as a fraction of the target.
     *
     * A quarter. A loop out and back by another way is a rough circle, and its
     * far point sits about a quarter of the perimeter away along the streets —
     * closer than the π-based radius of a true circle, because streets bend and
     * corner. It only has to be near enough for the refinement to converge.
     */
    private const val RADIUS_DIVISOR = 4.0

    /** How far from the aimed-at point a usable waypoint junction may sit. */
    private const val WAYPOINT_SLACK = 0.5

    /** Below this the loop is too small to be worth another refinement pass. */
    private const val MIN_RADIUS_METERS = 80.0

    /** Directions tried per call before giving up on this seed. */
    private const val BEARINGS_PER_PLAN = 3

    /** Radius refinements per direction. */
    private const val REFINEMENTS = 4

    /** ≈137.5° in radians — see [bearingFor]. */
    private const val GOLDEN_ANGLE = 2.399963229728653
}
