package io.app.enclose.geo

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The walkable network, as something a route can be searched over: junctions and
 * the stretches of path between them.
 *
 * ## Why a graph has to be *built* rather than read
 *
 * Vector tiles are drawing instructions, not a road network. Two facts about
 * them have to be undone before anything can be routed:
 *
 *  - **Roads are cut at tile edges.** [Mvt] clips each tile's copy to its own
 *    boundary, so a street crossing between two tiles arrives as two lines whose
 *    ends land on the same boundary but not on the same coordinate — each tile
 *    rounds to its own grid. Vertices are therefore *snapped*: two within
 *    [SNAP_METERS] are one junction.
 *  - **Lines are merged, not split at junctions.** A tile may carry a whole
 *    high street as one line and every side street as another, with no hint of
 *    where they meet. What they do share is the crossing *vertex*, because both
 *    ways in OpenStreetMap pass through the same node. So every vertex is a
 *    candidate junction, and connectivity falls out of snapping rather than out
 *    of any junction field in the data.
 *
 * That leaves a graph with a node per vertex, which is a few hundred thousand
 * nodes for a city's worth of tiles. Most of them are the middle of a street and
 * have nothing to decide, so runs of them are then contracted into one edge that
 * keeps its polyline: the search sees junctions only, and the drawing still gets
 * every bend.
 *
 * ## Distances
 *
 * Everything inside is planar metres from an equirectangular projection around
 * the data's own mean latitude — the same approximation [Geo] and [GeoClip]
 * already use, for the same reason. At the scale of a walk the error is under a
 * metre, and a search that recomputes haversine for every edge it relaxes is
 * doing trigonometry hundreds of thousands of times for an answer it could get
 * by subtracting.
 */
class PathGraph private constructor(
    private val nodeX: DoubleArray,
    private val nodeY: DoubleArray,
    private val nodeLat: DoubleArray,
    private val nodeLng: DoubleArray,
    /** Every stretch of path between two junctions. */
    val edges: List<Edge>,
    private val adjacency: Array<IntArray>,
    private val nodeIndex: Map<Long, IntArray>,
    /** cos(mean latitude), the one term the planar projection needs. */
    private val cosLat0: Double,
) {

    /**
     * A run of path between two junctions.
     *
     * [points] is the whole thing as it bends, from [a] to [b], so a route drawn
     * from these edges follows the street rather than cutting across the block.
     */
    class Edge(
        val a: Int,
        val b: Int,
        val points: List<LatLng>,
        val lengthMeters: Double,
        /** Length-weighted [WalkableWay.comfort] of what this edge is made of. */
        val comfort: Double,
    ) {
        /** The junction at the other end from [node]. */
        fun other(node: Int): Int = if (node == a) b else a

        /** [points] running from [from]'s end, so a route reads in order. */
        fun pointsFrom(from: Int): List<LatLng> = if (from == a) points else points.reversed()
    }

    val nodeCount: Int get() = nodeX.size

    /** Where junction [node] is. */
    fun position(node: Int): LatLng = LatLng(nodeLat[node], nodeLng[node])

    /** Indices into [edges] of everything meeting at [node]. */
    fun edgesAt(node: Int): IntArray = adjacency[node]

    /** Planar metres between two junctions — the search's own yardstick. */
    fun straightLineMeters(from: Int, to: Int): Double {
        val dx = nodeX[from] - nodeX[to]
        val dy = nodeY[from] - nodeY[to]
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * The junction nearest [point] within [withinMeters], or null.
     *
     * Null is a real answer and the caller has to say so out loud: standing in
     * the middle of a park, or somewhere the basemap has no paths for, there is
     * genuinely nothing to plan from, and a route that starts 800 m away is
     * worse than an honest "no route from here".
     */
    fun nearestNode(point: LatLng, withinMeters: Double = NEAR_START_METERS): Int? {
        val x = projectX(point.lng)
        val y = projectY(point.lat)
        val radius = (withinMeters / CELL_METERS).toInt().coerceAtLeast(1)
        val cellX = kotlin.math.floor(x / CELL_METERS).toInt()
        val cellY = kotlin.math.floor(y / CELL_METERS).toInt()
        var best = -1
        var bestDistance = withinMeters * withinMeters
        for (gy in (cellY - radius)..(cellY + radius)) {
            for (gx in (cellX - radius)..(cellX + radius)) {
                val cell = nodeIndex[cellKey(gx, gy)] ?: continue
                for (node in cell) {
                    val dx = nodeX[node] - x
                    val dy = nodeY[node] - y
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestDistance) {
                        bestDistance = d2
                        best = node
                    }
                }
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun projectX(lng: Double): Double = Math.toRadians(lng) * cosLat0 * EARTH_RADIUS_M
    private fun projectY(lat: Double): Double = Math.toRadians(lat) * EARTH_RADIUS_M

    companion object {

        /**
         * How far apart two vertices can be and still be the same junction.
         *
         * Set by the tile grid rather than by taste: at zoom 13 a tile's 4096
         * units span about 4 km, so a coordinate is rounded to roughly a metre,
         * and two tiles rounding the same boundary crossing independently can
         * disagree by a couple of them. Much larger than this and genuinely
         * separate paths — a footway beside the road it follows — would be
         * welded together.
         */
        const val SNAP_METERS = 4.0

        /**
         * How far the planner will look for something to start from. A long
         * block or a big car park can put a walker this far from the nearest
         * mapped way; much beyond it and the route starts somewhere else.
         */
        const val NEAR_START_METERS = 250.0

        /** Grid used by [nearestNode]; big enough that cells stay short. */
        private const val CELL_METERS = 100.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Build the network from [ways]. Empty in, empty out — a caller with no
         * tiles gets a graph that finds nothing rather than an exception.
         */
        fun build(ways: List<WalkableWay>, snapMeters: Double = SNAP_METERS): PathGraph {
            val builder = Builder(ways, snapMeters)
            return builder.build()
        }

        private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }

    /**
     * The two-pass construction described in the class docs: intern every vertex
     * on a snapping grid, then contract the runs between junctions.
     */
    private class Builder(private val ways: List<WalkableWay>, private val snapMeters: Double) {

        private val cosLat0: Double = run {
            var sum = 0.0
            var count = 0
            for (way in ways) {
                for (point in way.points) {
                    sum += point.lat
                    count++
                }
            }
            if (count == 0) 1.0 else cos(Math.toRadians(sum / count))
        }

        private val xs = ArrayList<Double>()
        private val ys = ArrayList<Double>()
        private val lats = ArrayList<Double>()
        private val lngs = ArrayList<Double>()
        private val grid = HashMap<Long, MutableList<Int>>()

        /** Per vertex, a bitmask of the decks it sits on — see [levelBit]. */
        private val levels = ArrayList<Int>()

        /** Per vertex: the segments leaving it, as (neighbour, comfort) pairs. */
        private val links = ArrayList<MutableList<Link>>()

        /** Every way's stretch between two interned vertices, before splitting. */
        private val segments = ArrayList<Segment>()

        private class Link(val to: Int, val comfort: Double) {
            var used = false
        }

        private class Segment(
            val from: Int,
            val to: Int,
            val comfort: Double,
            val level: Int,
        ) {
            /** Vertices found lying on this segment, as (position along it, vertex). */
            var splits: MutableList<Pair<Double, Int>>? = null
        }

        fun build(): PathGraph {
            for (way in ways) intern(way)
            splitAtTouchingVertices()
            for (segment in segments) link(segment)

            val edges = ArrayList<Edge>()
            val nodeOf = IntArray(xs.size) { -1 }
            val nodes = ArrayList<Int>()

            // Junctions are everything that isn't the middle of a path: ends,
            // crossroads, and the point where two ways of different comfort meet
            // is deliberately *not* one — the contraction below averages it.
            for (vertex in links.indices) {
                if (links[vertex].size != 2) {
                    nodeOf[vertex] = nodes.size
                    nodes.add(vertex)
                }
            }

            for (node in nodes.indices) {
                val vertex = nodes[node]
                for (link in links[vertex]) {
                    if (link.used) continue
                    val edge = trace(vertex, link, nodeOf, nodes) ?: continue
                    edges.add(edge)
                }
            }

            // Runs that never met a junction are closed rings floating on their
            // own — an island of path with no way onto it. Nothing can route
            // over them, so they are dropped rather than given an arbitrary
            // entry point.

            // Islands too small to walk a loop on are dropped here rather than
            // left to be found by a search — see [dropIslands].
            val kept = routableNodes(nodes.size, edges)
            val renumbered = IntArray(nodes.size) { -1 }
            val keptVertices = ArrayList<Int>(nodes.size)
            for (node in nodes.indices) {
                if (kept[node]) {
                    renumbered[node] = keptVertices.size
                    keptVertices.add(nodes[node])
                }
            }
            val routable = edges.filter { kept[it.a] }.map { edge ->
                Edge(
                    a = renumbered[edge.a],
                    b = renumbered[edge.b],
                    points = edge.points,
                    lengthMeters = edge.lengthMeters,
                    comfort = edge.comfort,
                )
            }
            edges.clear()
            edges.addAll(routable)
            val nodeCount = keptVertices.size

            val degree = IntArray(nodeCount)
            for (edge in edges) {
                degree[edge.a]++
                degree[edge.b]++
            }
            val adjacency = Array(nodeCount) { IntArray(degree[it]) }
            val filled = IntArray(nodeCount)
            for ((index, edge) in edges.withIndex()) {
                adjacency[edge.a][filled[edge.a]++] = index
                adjacency[edge.b][filled[edge.b]++] = index
            }

            val nodeX = DoubleArray(nodeCount) { xs[keptVertices[it]] }
            val nodeY = DoubleArray(nodeCount) { ys[keptVertices[it]] }
            val index = HashMap<Long, MutableList<Int>>()
            for (node in 0 until nodeCount) {
                val key = cellKey(
                    kotlin.math.floor(nodeX[node] / CELL_METERS).toInt(),
                    kotlin.math.floor(nodeY[node] / CELL_METERS).toInt(),
                )
                index.getOrPut(key) { ArrayList() }.add(node)
            }

            return PathGraph(
                nodeX = nodeX,
                nodeY = nodeY,
                nodeLat = DoubleArray(nodeCount) { lats[keptVertices[it]] },
                nodeLng = DoubleArray(nodeCount) { lngs[keptVertices[it]] },
                edges = edges,
                adjacency = adjacency,
                nodeIndex = index.mapValues { (_, list) -> list.toIntArray() },
                cosLat0 = cosLat0,
            )
        }

        /**
         * Which nodes belong to a piece of network worth routing over.
         *
         * A city tile decodes into one big connected network and a scattering of
         * fragments — a service road behind a building whose link to the street
         * was simplified away, a footpath drawn inside a park with nothing
         * joining it. On one real tile of Athens: 3 754 nodes in the network and
         * 74 fragments around it, one of which happened to be **the two nodes
         * nearest the middle of the tile**. `nearestNode` duly handed the
         * planner a node on a two-node island and every search died instantly,
         * with a graph that was otherwise entirely healthy.
         *
         * So fragments are removed at build time rather than guarded against at
         * search time. Dropping them is honest: a loop has to start and finish
         * where the walker is standing, so a piece of network they cannot reach
         * on foot from there is not a route, and "no path near you" is the true
         * answer when the only thing nearby is an island.
         */
        private fun routableNodes(nodeCount: Int, edges: List<Edge>): BooleanArray {
            val adjacency = Array(nodeCount) { ArrayList<Int>(2) }
            for (edge in edges) {
                adjacency[edge.a].add(edge.b)
                adjacency[edge.b].add(edge.a)
            }

            val componentOf = IntArray(nodeCount) { -1 }
            val sizes = ArrayList<Int>()
            val stack = ArrayList<Int>()
            for (start in 0 until nodeCount) {
                if (componentOf[start] >= 0) continue
                val id = sizes.size
                var size = 0
                stack.clear()
                stack.add(start)
                componentOf[start] = id
                while (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.size - 1)
                    size++
                    for (next in adjacency[node]) {
                        if (componentOf[next] < 0) {
                            componentOf[next] = id
                            stack.add(next)
                        }
                    }
                }
                sizes.add(size)
            }

            // Relative, not absolute. What makes a fragment a fragment is the
            // network it sits beside: five junctions next to four thousand is
            // an orphaned service road, whereas five junctions and nothing else
            // is simply a small place — and refusing to route there because a
            // city would have had more would be nonsense.
            val largest = sizes.maxOrNull() ?: 0
            val threshold = kotlin.math.ceil(largest * ISLAND_FRACTION).toInt().coerceAtLeast(1)
            val keep = BooleanArray(nodeCount)
            for (node in 0 until nodeCount) {
                keep[node] = sizes[componentOf[node]] >= threshold
            }
            return keep
        }

        /**
         * Follow a run of two-link vertices from [start] until it reaches
         * another junction, gathering the polyline as it goes.
         *
         * Returns null when the run leads nowhere usable — a way doubling back
         * onto itself, or a ring that closes without ever passing a junction.
         */
        private fun trace(start: Int, first: Link, nodeOf: IntArray, nodes: List<Int>): Edge? {
            val points = ArrayList<LatLng>()
            points.add(LatLng(lats[start], lngs[start]))
            var previous = start
            var link = first
            var length = 0.0
            var weighted = 0.0
            var steps = 0

            while (true) {
                link.used = true
                val next = link.to
                // Mark the reverse of this segment used as well, or the run gets
                // traced a second time from the far end as a duplicate edge.
                links[next].firstOrNull { it.to == previous && !it.used }?.used = true

                val segment = distance(previous, next)
                length += segment
                weighted += segment * link.comfort
                points.add(LatLng(lats[next], lngs[next]))

                if (nodeOf[next] >= 0) {
                    val a = nodeOf[start]
                    val b = nodeOf[next]
                    if (a < 0 || b < 0 || length <= 0.0) return null
                    return Edge(
                        a = a,
                        b = b,
                        points = points,
                        lengthMeters = length,
                        comfort = weighted / length,
                    )
                }

                // A vertex in the middle of a path has exactly two links: carry
                // on down the one we didn't arrive by.
                val onward = links[next].firstOrNull { it.to != previous && !it.used }
                    ?: links[next].firstOrNull { !it.used }
                    ?: return null
                previous = next
                link = onward
                // A guard, not a limit that any real path reaches: a corrupt
                // stretch of geometry must not be able to walk forever.
                if (++steps > MAX_RUN) return null
            }
        }

        private fun intern(way: WalkableWay) {
            var previous = -1
            for (point in way.points) {
                val vertex = vertexAt(point)
                // Which decks this vertex sits on. A vertex shared by a bridge
                // and the street it lands on is on both, and must stay joinable
                // to either.
                levels[vertex] = levels[vertex] or levelBit(way.level)
                if (vertex != previous) {
                    if (previous >= 0) {
                        segments.add(Segment(previous, vertex, way.comfort, way.level))
                    }
                    previous = vertex
                }
            }
        }

        /**
         * Join ways that meet without sharing a vertex.
         *
         * **This is what makes the graph a network rather than three thousand
         * loose streets.** Tiles are simplified for drawing, and simplification
         * removes vertices that don't change a line's shape — including the
         * junction node where a side street meets a main road running straight
         * through it. Both ways still pass through the same point on the ground,
         * but only the side street has a vertex there, so vertex-to-vertex
         * snapping alone finds nothing and every T-junction in the city
         * disappears. (Measured on one real tile of Athens: 3 006 edges in
         * ~3 000 disconnected pieces, and not one loop findable anywhere.)
         *
         * So a vertex lying on another way's segment splits it. Two guards keep
         * that from inventing junctions:
         *
         *  - **Levels must match.** A footbridge crosses the road beneath it at a
         *    point on the map and is not a junction. Ways that *share a vertex*
         *    are joined regardless — that is how a bridge meets the road it lands
         *    on — so this only governs crossings.
         *  - **Only strictly inside the segment.** A vertex near an end would
         *    already have been merged by [vertexAt] if it belonged there, and
         *    splitting at an endpoint produces a zero-length stub.
         */
        private fun splitAtTouchingVertices() {
            if (segments.isEmpty()) return
            val cells = HashMap<Long, MutableList<Int>>()
            for ((index, segment) in segments.withIndex()) {
                forEachCell(segment) { key -> cells.getOrPut(key) { ArrayList() }.add(index) }
            }

            for (vertex in xs.indices) {
                val x = xs[vertex]
                val y = ys[vertex]
                val cellX = kotlin.math.floor(x / TOUCH_CELL_METERS).toInt()
                val cellY = kotlin.math.floor(y / TOUCH_CELL_METERS).toInt()
                // The 3×3 neighbourhood: a segment passing a couple of metres
                // away can cross the next cell without ever entering this one.
                for (gy in (cellY - 1)..(cellY + 1)) {
                    for (gx in (cellX - 1)..(cellX + 1)) {
                        val candidates = cells[cellKey(gx, gy)] ?: continue
                        for (index in candidates) {
                            val segment = segments[index]
                            if (segment.from == vertex || segment.to == vertex) continue
                            // Same deck only — see the docs above.
                            if (levels[vertex] and levelBit(segment.level) == 0) continue
                            val t = projectionOf(x, y, segment) ?: continue
                            val splits = segment.splits
                                ?: ArrayList<Pair<Double, Int>>().also { segment.splits = it }
                            if (splits.none { it.second == vertex }) splits.add(t to vertex)
                        }
                    }
                }
            }
        }

        /**
         * How far along [segment] the point sits, or null when it is too far off
         * it (or too near an end) to be the same place.
         */
        private fun projectionOf(x: Double, y: Double, segment: Segment): Double? {
            val ax = xs[segment.from]
            val ay = ys[segment.from]
            val dx = xs[segment.to] - ax
            val dy = ys[segment.to] - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0.0) return null
            val t = ((x - ax) * dx + (y - ay) * dy) / lengthSquared
            if (t <= 0.0 || t >= 1.0) return null
            val offX = ax + t * dx - x
            val offY = ay + t * dy - y
            if (offX * offX + offY * offY > TOUCH_METERS * TOUCH_METERS) return null
            // A split this close to an end is the end, and would leave a stub
            // shorter than the snapping tolerance behind it.
            val length = kotlin.math.sqrt(lengthSquared)
            if (t * length < TOUCH_METERS || (1 - t) * length < TOUCH_METERS) return null
            return t
        }

        /** Every grid cell [segment] passes through, at [TOUCH_CELL_METERS]. */
        private inline fun forEachCell(segment: Segment, action: (Long) -> Unit) {
            val ax = xs[segment.from]
            val ay = ys[segment.from]
            val bx = xs[segment.to]
            val by = ys[segment.to]
            val minX = kotlin.math.floor(minOf(ax, bx) / TOUCH_CELL_METERS).toInt()
            val maxX = kotlin.math.floor(maxOf(ax, bx) / TOUCH_CELL_METERS).toInt()
            val minY = kotlin.math.floor(minOf(ay, by) / TOUCH_CELL_METERS).toInt()
            val maxY = kotlin.math.floor(maxOf(ay, by) / TOUCH_CELL_METERS).toInt()
            // The bounding box rather than the line itself: a segment is short
            // after splitting and the extra cells cost one distance check each,
            // whereas a missed cell costs a junction.
            if ((maxX - minX + 1).toLong() * (maxY - minY + 1) > MAX_SEGMENT_CELLS) return
            for (gy in minY..maxY) {
                for (gx in minX..maxX) action(cellKey(gx, gy))
            }
        }

        /** Turn one segment — and anything found lying on it — into links. */
        private fun link(segment: Segment) {
            val splits = segment.splits
            if (splits == null) {
                connect(segment.from, segment.to, segment.comfort)
                return
            }
            splits.sortBy { it.first }
            var previous = segment.from
            for ((_, vertex) in splits) {
                if (vertex == previous) continue
                connect(previous, vertex, segment.comfort)
                previous = vertex
            }
            if (previous != segment.to) connect(previous, segment.to, segment.comfort)
        }

        /** Undirected: a stretch is walkable both ways, and oneway is for cars. */
        private fun connect(from: Int, to: Int, comfort: Double) {
            if (from == to) return
            links[from].add(Link(to, comfort))
            links[to].add(Link(from, comfort))
        }

        /** The interned vertex for [point], creating one if nothing is near. */
        private fun vertexAt(point: LatLng): Int {
            val x = Math.toRadians(point.lng) * cosLat0 * EARTH_RADIUS_M
            val y = Math.toRadians(point.lat) * EARTH_RADIUS_M
            val cellX = kotlin.math.floor(x / snapMeters).toInt()
            val cellY = kotlin.math.floor(y / snapMeters).toInt()
            var best = -1
            var bestDistance = snapMeters * snapMeters
            // The 3×3 neighbourhood, because a point near a cell edge has its
            // nearest neighbour in the next cell along.
            for (gy in (cellY - 1)..(cellY + 1)) {
                for (gx in (cellX - 1)..(cellX + 1)) {
                    val cell = grid[cellKey(gx, gy)] ?: continue
                    for (vertex in cell) {
                        val dx = xs[vertex] - x
                        val dy = ys[vertex] - y
                        val d2 = dx * dx + dy * dy
                        if (d2 < bestDistance) {
                            bestDistance = d2
                            best = vertex
                        }
                    }
                }
            }
            if (best >= 0) return best

            val vertex = xs.size
            xs.add(x)
            ys.add(y)
            lats.add(point.lat)
            lngs.add(point.lng)
            levels.add(0)
            links.add(ArrayList(2))
            grid.getOrPut(cellKey(cellX, cellY)) { ArrayList() }.add(vertex)
            return vertex
        }

        private fun distance(from: Int, to: Int): Double {
            val dx = xs[from] - xs[to]
            val dy = ys[from] - ys[to]
            return sqrt(dx * dx + dy * dy)
        }

        /**
         * A deck, as one bit. Clamped to ±3: real data has layers of ±5 here and
         * there, and a fourth-storey flyover and a third-storey one not being
         * distinguished costs nothing that a walker would notice.
         */
        private fun levelBit(level: Int): Int = 1 shl (level.coerceIn(-3, 3) + 3)

        private companion object {
            /** Vertices in one contracted run before it's judged nonsense. */
            const val MAX_RUN = 100_000

            /**
             * How far off a way a vertex may be and still be *on* it.
             *
             * Larger than [SNAP_METERS], because this bridges a different error:
             * not two tiles rounding a boundary, but a simplified line drifting
             * from where the junction actually is. Small enough that a footway
             * running alongside a road is not repeatedly welded to it.
             */
            const val TOUCH_METERS = 8.0

            /** Grid for the vertex-onto-segment search. */
            const val TOUCH_CELL_METERS = 40.0

            /**
             * How small a piece of network has to be, next to the biggest one,
             * before it counts as a stray rather than a place — see
             * [routableNodes].
             *
             * A fiftieth. On the tile that exposed this, the network was 3 754
             * junctions and every fragment was five or fewer, so anything from a
             * hundredth to a tenth would have drawn the same line; the point is
             * that it is measured against the neighbourhood rather than fixed.
             */
            const val ISLAND_FRACTION = 0.02

            /**
             * A segment spanning more cells than this is a stray line across the
             * county — indexing it would cost more than the junctions it could
             * possibly find.
             */
            const val MAX_SEGMENT_CELLS = 4_096
        }
    }
}
