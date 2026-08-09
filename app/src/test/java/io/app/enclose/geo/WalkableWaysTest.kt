package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which lines a person may be sent down.
 *
 * The first test here is the one that matters: a router that hands somebody a
 * motorway slip road because it was shorter has failed at the only part of this
 * job that can get them hurt.
 */
class WalkableWaysTest {

    @Test
    fun `motorways and trunk roads are never walkable`() {
        assertNull(WalkableWays.of(line("class" to "motorway")))
        assertNull(WalkableWays.of(line("class" to "trunk")))
    }

    /**
     * An allowlist, not a blocklist: tiles gain classes over time, and the safe
     * answer for one nobody has considered is "not on foot".
     */
    @Test
    fun `unknown and non-road classes are refused`() {
        assertNull(WalkableWays.of(line("class" to "rail")))
        assertNull(WalkableWays.of(line("class" to "ferry")))
        assertNull(WalkableWays.of(line("class" to "aerialway")))
        assertNull(WalkableWays.of(line("class" to "hyperloop")))
        assertNull(WalkableWays.of(line()))
    }

    @Test
    fun `ordinary streets and paths are walkable`() {
        assertNotNull(WalkableWays.of(line("class" to "minor")))
        assertNotNull(WalkableWays.of(line("class" to "path")))
        assertNotNull(WalkableWays.of(line("class" to "primary")))
    }

    /** Comfort is what steers a route, so the ordering is the contract. */
    @Test
    fun `pleasant ways cost less than main roads`() {
        val footway = WalkableWays.of(line("class" to "path", "subclass" to "footway"))!!
        val residential = WalkableWays.of(line("class" to "minor"))!!
        val primary = WalkableWays.of(line("class" to "primary"))!!

        assertTrue(footway.comfort < residential.comfort)
        assertTrue(residential.comfort < primary.comfort)
    }

    /**
     * Steps are walkable and often the nicest way up a hill, but they rule the
     * route out for a pram and wreck any time estimate — costly, never banned.
     */
    @Test
    fun `steps are expensive but allowed`() {
        val steps = WalkableWays.of(line("class" to "path", "subclass" to "steps"))

        assertNotNull(steps)
        assertTrue(steps!!.comfort > WalkableWays.of(line("class" to "minor"))!!.comfort)
    }

    @Test
    fun `access restrictions are obeyed, and foot overrides them`() {
        assertNull(WalkableWays.of(line("class" to "minor", "foot" to "no")))
        assertNull(WalkableWays.of(line("class" to "service", "access" to "private")))
        assertNotNull(
            WalkableWays.of(
                line("class" to "service", "access" to "private", "foot" to "designated"),
            ),
        )
    }

    @Test
    fun `indoor corridors are not routes`() {
        assertNull(WalkableWays.of(line("class" to "path", "indoor" to "1")))
    }

    @Test
    fun `a line of one point carries no length and is dropped`() {
        val single = Mvt.Line(mapOf("class" to "minor"), listOf(LatLng(1.0, 1.0)))

        assertNull(WalkableWays.of(single))
    }

    @Test
    fun `the batch form keeps only what is walkable`() {
        val ways = WalkableWays.of(
            listOf(
                line("class" to "minor"),
                line("class" to "motorway"),
                line("class" to "path"),
            ),
        )

        assertEquals(2, ways.size)
    }

    private fun line(vararg tags: Pair<String, String>) = Mvt.Line(
        tags = tags.toMap(),
        points = listOf(LatLng(37.98, 23.72), LatLng(37.981, 23.721)),
    )
}
