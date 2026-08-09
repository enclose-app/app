package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offering back a walk that has already been done.
 *
 * The rules are all about the walker standing there now: near enough to set off
 * on, and close enough to the distance they asked for. A route that fails either
 * is not a worse suggestion, it is the wrong one.
 */
class PastRoutesTest {

    /** A square of 400 m sides — a 1.6 km loop. */
    private val square = listOf(
        corner(0, 0),
        corner(4, 0),
        corner(4, 4),
        corner(0, 4),
    )

    @Test
    fun `a loop of about the right length, near enough to start, is offered`() {
        val matches = PastRoutes.matching(
            walks = listOf(walk("a", square)),
            from = corner(0, 0),
            targetMeters = 1_600.0,
        )

        assertEquals(1, matches.size)
        assertEquals("a", matches[0].walkId)
        assertEquals(1_600.0, matches[0].lengthMeters, 40.0)
    }

    /** Rings are implicitly closed in this app; a route to follow has to show it. */
    @Test
    fun `the offered route is closed`() {
        val match = PastRoutes.matching(listOf(walk("a", square)), corner(0, 0), 1_600.0).single()

        assertEquals(match.route.first(), match.route.last())
        assertEquals(square.size + 1, match.route.size)
    }

    /**
     * Rotated to begin at the near end. Following a loop from the far corner
     * means walking to it first, which is not what "starts from where you are"
     * means.
     */
    @Test
    fun `the route starts at the point nearest the walker`() {
        val near = corner(4, 4)
        val match = PastRoutes.matching(listOf(walk("a", square)), near, 1_600.0).single()

        assertEquals(near, match.route.first())
        assertTrue(match.startsAwayMeters < 1.0)
    }

    @Test
    fun `a loop of the wrong length is not offered`() {
        val matches = PastRoutes.matching(listOf(walk("a", square)), corner(0, 0), 5_000.0)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a loop across town is not offered`() {
        val matches = PastRoutes.matching(
            walks = listOf(walk("a", square)),
            from = LatLng(37.98 + 0.05, 23.72),
            targetMeters = 1_600.0,
        )

        assertTrue(matches.isEmpty())
    }

    /** Nearest the asked-for distance first; the newer walk breaks a tie. */
    @Test
    fun `matches are ordered by fit, then by recency`() {
        // 1.8 km: a worse fit than the square, but still inside the tolerance.
        val bigger = listOf(corner(0, 0), corner(5, 0), corner(5, 4), corner(0, 4))
        val matches = PastRoutes.matching(
            walks = listOf(
                walk("bigger", bigger, closedAt = 3_000),
                walk("old", square, closedAt = 1_000),
                walk("new", square, closedAt = 2_000),
            ),
            from = corner(0, 0),
            targetMeters = 1_600.0,
        )

        assertEquals(listOf("new", "old"), matches.take(2).map { it.walkId })
        assertEquals(3, matches.size)
        assertEquals("bigger", matches[2].walkId)
    }

    @Test
    fun `something that isn't a ring is ignored`() {
        val matches = PastRoutes.matching(
            walks = listOf(walk("a", listOf(corner(0, 0), corner(1, 0)))),
            from = corner(0, 0),
            targetMeters = 1_600.0,
        )

        assertTrue(matches.isEmpty())
    }

    // --- fixtures ------------------------------------------------------------

    private fun corner(x: Int, y: Int) = LatLng(
        lat = 37.98 + y * STEP_LAT,
        lng = 23.72 + x * STEP_LNG,
    )

    private fun walk(id: String, ring: List<LatLng>, closedAt: Long = 1_000) = Walk(
        id = id,
        ring = ring,
        areaSqMeters = Geo.polygonAreaSqMeters(ring),
        perimeterMeters = Geo.pathLengthMeters(ring + ring.first()),
        distanceToStartMeters = 5.0,
        closedAtEpochMs = closedAt,
        claimed = true,
    )

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
        const val STEP_LAT = 100.0 / METERS_PER_DEGREE_LAT
        const val STEP_LNG = 100.0 / (METERS_PER_DEGREE_LAT * 0.788)
    }
}
