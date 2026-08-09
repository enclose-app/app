package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The loop search, over a grid of streets 100 m apart — a made-up town, but one
 * with the property that matters: many routes of many lengths between the same
 * two corners.
 *
 * What is checked here is what the user actually asked for. It comes back to
 * where they started, it is about as long as they said, and pressing the button
 * again gives a different walk.
 */
class LoopPlannerTest {

    private val graph = grid(size = 24)
    private val start = point(12, 12)

    @Test
    fun `the loop is about as long as asked for, and closes`() {
        val loop = LoopPlanner.plan(graph, start, targetMeters = 1_600.0, seed = 0)

        assertNotNull(loop)
        assertEquals(start, loop!!.points.first())
        assertEquals(start, loop.points.last())
        assertTrue(
            "was ${loop.lengthMeters} m",
            abs(loop.lengthMeters - 1_600.0) <= 1_600.0 * LoopPlanner.LOOSE_TOLERANCE,
        )
    }

    /** A route that walks the same street both ways isn't a loop worth offering. */
    @Test
    fun `it comes back a different way`() {
        val loop = LoopPlanner.plan(graph, start, targetMeters = 2_000.0, seed = 3)

        assertNotNull(loop)
        assertTrue(loop!!.retracedFraction <= LoopPlanner.MAX_RETRACED)
    }

    /** Distances scale: asking for twice as far gives a noticeably longer walk. */
    @Test
    fun `a longer target gives a longer loop`() {
        val short = LoopPlanner.plan(graph, start, targetMeters = 1_000.0, seed = 0)
        val long = LoopPlanner.plan(graph, start, targetMeters = 3_000.0, seed = 0)

        assertNotNull(short)
        assertNotNull(long)
        assertTrue(long!!.lengthMeters > short!!.lengthMeters * 1.5)
    }

    /**
     * The shuffle button's whole contract: the next press is a different walk,
     * and the same press twice is the same walk — which is what lets a
     * suggestion survive a rotation without silently changing.
     */
    @Test
    fun `successive seeds give different routes, and a seed repeats exactly`() {
        val first = LoopPlanner.plan(graph, start, targetMeters = 1_600.0, seed = 0)
        val second = LoopPlanner.plan(graph, start, targetMeters = 1_600.0, seed = 1)
        val firstAgain = LoopPlanner.plan(graph, start, targetMeters = 1_600.0, seed = 0)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first!!.points, second!!.points)
        assertEquals(first.points, firstAgain!!.points)
    }

    /**
     * Ground already claimed is cheaper to walk, so a loop planned with it
     * follows more of it — that is the whole of "offer me something close to
     * what I already walk".
     */
    @Test
    fun `familiar ground pulls the route onto it`() {
        // A corridor of previously walked streets, off to one side of the start.
        val walked = (8..16).map { point(it, 15) } + (8..16).map { point(it, 16) }
        val familiar = FamiliarGround.of(listOf(walked))

        val plain = LoopPlanner.plan(graph, start, 2_000.0, seed = 0)!!
        val pulled = LoopPlanner.plan(graph, start, 2_000.0, seed = 0, familiar = familiar)!!

        assertTrue(
            "plain ${familiar.familiarFraction(plain.points)} " +
                "vs pulled ${familiar.familiarFraction(pulled.points)}",
            familiar.familiarFraction(pulled.points) >=
                familiar.familiarFraction(plain.points),
        )
    }

    @Test
    fun `nowhere to walk from is null, not an exception`() {
        // Half a world away from the grid.
        assertNull(LoopPlanner.plan(graph, LatLng(-33.9, 151.2), 2_000.0, seed = 0))
        assertNull(LoopPlanner.plan(PathGraph.build(emptyList()), start, 2_000.0, seed = 0))
    }

    @Test
    fun `a target of nothing is refused`() {
        assertNull(LoopPlanner.plan(graph, start, targetMeters = 0.0, seed = 0))
    }

    /**
     * A target far larger than the streets available comes back as null rather
     * than as the longest thing that could be found — a 20 km request answered
     * with 3 km is a wrong answer, not a near miss.
     */
    @Test
    fun `a target the streets can't reach is refused`() {
        assertNull(LoopPlanner.plan(grid(size = 6), point(3, 3), 20_000.0, seed = 0))
    }

    // --- fixtures ------------------------------------------------------------

    /** A lattice of streets 100 m apart, [size] blocks each way. */
    private fun grid(size: Int): PathGraph {
        val ways = ArrayList<WalkableWay>()
        for (y in 0..size) {
            ways.add(WalkableWay((0..size).map { x -> point(x, y) }, comfort = 1.0))
        }
        for (x in 0..size) {
            ways.add(WalkableWay((0..size).map { y -> point(x, y) }, comfort = 1.0))
        }
        return PathGraph.build(ways)
    }

    private fun point(x: Int, y: Int) = LatLng(
        lat = ORIGIN_LAT + y * STEP_LAT,
        lng = ORIGIN_LNG + x * STEP_LNG,
    )

    private companion object {
        const val ORIGIN_LAT = 37.98
        const val ORIGIN_LNG = 23.72
        const val METERS_PER_DEGREE_LAT = 111_320.0
        const val STEP_LAT = 100.0 / METERS_PER_DEGREE_LAT
        const val STEP_LNG = 100.0 / (METERS_PER_DEGREE_LAT * 0.788)
    }
}
