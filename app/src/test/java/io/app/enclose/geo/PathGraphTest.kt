package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning drawing instructions back into a network.
 *
 * Both of the awkward properties of vector tiles are pinned here, because both
 * of them fail the same way when they regress — the route search finds nothing,
 * and nothing about the map on screen suggests why.
 */
class PathGraphTest {

    /**
     * Tiles carry merged lines with no junction information. What two crossing
     * streets do share is the vertex where they meet, and that is the only thing
     * connectivity can be rebuilt from.
     */
    @Test
    fun `two ways crossing at a shared vertex make a junction`() {
        val middle = point(1, 1)
        val graph = PathGraph.build(
            listOf(
                street(point(0, 1), middle, point(2, 1)),
                street(point(1, 0), middle, point(1, 2)),
            ),
        )

        val junction = graph.nearestNode(middle, 5.0)
        assertNotNull(junction)
        assertEquals(4, graph.edgesAt(junction!!).size)
    }

    /**
     * The same street arriving from two tiles is cut at their shared boundary,
     * and each tile rounds that crossing to its own grid. A couple of metres
     * apart has to mean "the same place", or every tile edge is a hole.
     */
    @Test
    fun `ends a couple of metres apart are the same junction`() {
        val graph = PathGraph.build(
            listOf(
                street(point(0, 0), point(1, 0)),
                // Starts two metres north of where the first one ended.
                street(nudge(point(1, 0), 2.0), point(2, 0)),
            ),
        )

        // One run end to end: the joint is not a junction, so it contracts away.
        assertEquals(2, graph.nodeCount)
        assertEquals(1, graph.edges.size)
        assertEquals(200.0, graph.edges[0].lengthMeters, 5.0)
    }

    /**
     * The bug that made the whole feature useless, pinned.
     *
     * Tiles are simplified for drawing, and simplification drops vertices that
     * don't change a line's shape — including the junction where a side street
     * meets a main road running straight past it. The side street's end then
     * sits *on* the main road with no vertex in common, and vertex-to-vertex
     * snapping alone finds nothing: measured on one real tile of Athens, that
     * was 3 000 disconnected fragments and not a single loop findable.
     */
    @Test
    fun `a street ending on another street's middle is a junction`() {
        val throughRoad = street(point(0, 0), point(4, 0))
        val sideStreet = street(point(2, 2), point(2, 0))

        val graph = PathGraph.build(listOf(throughRoad, sideStreet))

        val junction = graph.nearestNode(point(2, 0), 5.0)
        assertNotNull(junction)
        // The through road is cut in two there, and the side street joins it.
        assertEquals(3, graph.edgesAt(junction!!).size)
    }

    /**
     * ...but not where they only cross on the map. A footbridge over a road
     * meets it nowhere, and a route that joined them would send someone over a
     * parapet.
     */
    @Test
    fun `a bridge crossing a road is not a junction`() {
        val road = street(point(0, 0), point(4, 0))
        val bridge = WalkableWay(
            listOf(point(2, -2), point(2, 0), point(2, 2)),
            comfort = 1.0,
            level = 1,
        )

        val graph = PathGraph.build(listOf(road, bridge))

        // Two separate ways, each end to end: four junctions, two edges.
        assertEquals(4, graph.nodeCount)
        assertEquals(2, graph.edges.size)
    }

    /** A bridge still meets the road it lands on, where they share an end. */
    @Test
    fun `a bridge joins what it lands on`() {
        val road = street(point(0, 0), point(2, 0))
        val bridge = WalkableWay(
            listOf(point(2, 0), point(4, 0)),
            comfort = 1.0,
            level = 1,
        )

        val graph = PathGraph.build(listOf(road, bridge))

        assertEquals(2, graph.nodeCount)
        assertEquals(1, graph.edges.size)
    }

    /**
     * A handful of junctions beside a whole neighbourhood is an orphaned service
     * road, and `nearestNode` handing the planner one of those is how a search
     * dies instantly over a graph that is otherwise perfectly healthy.
     */
    @Test
    fun `a fragment beside a real network is dropped`() {
        val ways = ArrayList<WalkableWay>()
        for (y in 0..14) ways.add(street(*(0..14).map { x -> point(x, y) }.toTypedArray()))
        for (x in 0..14) ways.add(street(*(0..14).map { y -> point(x, y) }.toTypedArray()))
        // A stray pair of junctions well away from all of it.
        ways.add(street(point(40, 40), point(41, 40)))

        val graph = PathGraph.build(ways)

        assertNull(graph.nearestNode(point(40, 40), 50.0))
        assertNotNull(graph.nearestNode(point(7, 7), 50.0))
    }

    /** ...but a small place with nothing beside it is a place, not a fragment. */
    @Test
    fun `a small network on its own is kept`() {
        val graph = PathGraph.build(listOf(street(point(0, 0), point(1, 0), point(1, 1))))

        assertEquals(2, graph.nodeCount)
        assertNotNull(graph.nearestNode(point(0, 0), 10.0))
    }

    /** Far enough apart is a gap, and inventing a link across it is worse. */
    @Test
    fun `ends far apart stay separate`() {
        val graph = PathGraph.build(
            listOf(
                street(point(0, 0), point(1, 0)),
                street(nudge(point(1, 0), 40.0), point(2, 0)),
            ),
        )

        assertEquals(4, graph.nodeCount)
        assertEquals(2, graph.edges.size)
    }

    /**
     * A street with nothing joining it is one edge however many bends it has —
     * the search sees junctions, the drawing still gets every corner.
     */
    @Test
    fun `a run of plain vertices contracts to one edge that keeps its shape`() {
        val bends = (0..10).map { point(it, 0) }
        val graph = PathGraph.build(listOf(street(*bends.toTypedArray())))

        assertEquals(2, graph.nodeCount)
        assertEquals(1, graph.edges.size)
        assertEquals(11, graph.edges[0].points.size)
        assertEquals(1000.0, graph.edges[0].lengthMeters, 20.0)
    }

    /** Comfort follows the ground, averaged by how much of each you walk. */
    @Test
    fun `an edge's comfort is the length-weighted average of what it's made of`() {
        val graph = PathGraph.build(
            listOf(
                WalkableWay(listOf(point(0, 0), point(1, 0)), comfort = 1.0),
                WalkableWay(listOf(point(1, 0), point(2, 0)), comfort = 2.0),
            ),
        )

        assertEquals(1, graph.edges.size)
        assertEquals(1.5, graph.edges[0].comfort, 0.01)
    }

    /** A ring with no way onto it can't be routed over, so it isn't kept. */
    @Test
    fun `an isolated ring is dropped`() {
        val ring = listOf(point(0, 0), point(1, 0), point(1, 1), point(0, 1), point(0, 0))
        val graph = PathGraph.build(listOf(street(*ring.toTypedArray())))

        assertEquals(0, graph.edges.size)
    }

    @Test
    fun `nearest node respects its radius`() {
        val graph = PathGraph.build(listOf(street(point(0, 0), point(1, 0), point(2, 0))))

        assertNotNull(graph.nearestNode(point(0, 0), 10.0))
        // The middle of the street is not a junction, so the nearest *node* is
        // 100 m away at either end.
        assertNull(graph.nearestNode(point(1, 0), 10.0))
        assertNotNull(graph.nearestNode(point(1, 0), 150.0))
    }

    @Test
    fun `an empty tile makes an empty graph rather than an exception`() {
        val graph = PathGraph.build(emptyList())

        assertEquals(0, graph.nodeCount)
        assertNull(graph.nearestNode(point(0, 0), 1000.0))
    }

    @Test
    fun `edge geometry reads in the direction it is walked`() {
        val graph = PathGraph.build(
            listOf(
                street(point(0, 0), point(1, 0)),
                street(point(1, 0), point(1, 1)),
            ),
        )
        val edge = graph.edges.first()

        assertEquals(edge.points, edge.pointsFrom(edge.a))
        assertEquals(edge.points.reversed(), edge.pointsFrom(edge.b))
        assertTrue(edge.other(edge.a) == edge.b)
    }

    // --- fixtures ------------------------------------------------------------

    /** A point on a 100 m grid, [x] east and [y] north of the origin. */
    private fun point(x: Int, y: Int) = LatLng(
        lat = ORIGIN_LAT + y * STEP_LAT,
        lng = ORIGIN_LNG + x * STEP_LNG,
    )

    private fun nudge(point: LatLng, meters: Double) =
        LatLng(point.lat + meters / METERS_PER_DEGREE_LAT, point.lng)

    private fun street(vararg points: LatLng) = WalkableWay(points.toList(), comfort = 1.0)

    private companion object {
        const val ORIGIN_LAT = 37.98
        const val ORIGIN_LNG = 23.72
        const val METERS_PER_DEGREE_LAT = 111_320.0

        /** 100 m of latitude, and 100 m of longitude at this latitude. */
        const val STEP_LAT = 100.0 / METERS_PER_DEGREE_LAT
        const val STEP_LNG = 100.0 / (METERS_PER_DEGREE_LAT * 0.788)
    }
}
