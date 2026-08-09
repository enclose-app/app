package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.WalkableArea
import io.app.enclose.geo.WalkableWay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order suggestions come out in, and what happens when there is nothing to
 * suggest.
 *
 * The tile fetch is faked, which is the point of the [WalkableArea] seam: what
 * is being tested is the ordering and the failure reporting, neither of which
 * should need a network to check.
 */
class RouteSuggesterTest {

    private val start = point(12, 12)

    /** Evidence before guesswork: a walk already done is offered first. */
    @Test
    fun `a previously walked loop comes before a planned one`() = runBlocking {
        val outcome = suggester().suggest(
            request(attempt = 0, pastWalks = listOf(walk("done", squareAround(start)))),
        )

        val found = outcome as RouteOutcome.Found
        assertEquals(RouteOrigin.WALKED_BEFORE, found.suggestion.origin)
        assertEquals(0, found.suggestion.attempt)
    }

    /** Shuffling past the walked ones lands on a planned loop. */
    @Test
    fun `the next attempt moves on to a planned route`() = runBlocking {
        val outcome = suggester().suggest(
            request(attempt = 1, pastWalks = listOf(walk("done", squareAround(start)))),
        )

        val found = outcome as RouteOutcome.Found
        assertEquals(RouteOrigin.PLANNED, found.suggestion.origin)
        assertTrue(found.suggestion.route.isNotEmpty())
        assertEquals(start, found.suggestion.route.first())
        assertEquals(start, found.suggestion.route.last())
    }

    @Test
    fun `with no history at all the first suggestion is planned`() = runBlocking {
        val outcome = suggester().suggest(request(attempt = 0))

        assertEquals(RouteOrigin.PLANNED, (outcome as RouteOutcome.Found).suggestion.origin)
    }

    @Test
    fun `no tiles means no data, and it says so`() = runBlocking {
        val outcome = RouteSuggester(NoArea).suggest(request(attempt = 0))

        assertEquals(RouteUnavailable.NO_DATA, (outcome as RouteOutcome.None).reason)
    }

    /**
     * Roads came back, but none near the walker. Distinct from having no data:
     * one is the network, the other is where they're standing.
     */
    @Test
    fun `roads that are nowhere near the walker report as such`() = runBlocking {
        val outcome = suggester().suggest(
            request(attempt = 0).copy(from = LatLng(-33.9, 151.2)),
        )

        assertEquals(RouteUnavailable.NO_PATHS_NEARBY, (outcome as RouteOutcome.None).reason)
    }

    @Test
    fun `a distance outside the range is refused rather than attempted`() = runBlocking {
        val tooShort = suggester().suggest(request(attempt = 0).copy(targetMeters = 50.0))
        val tooLong = suggester().suggest(request(attempt = 0).copy(targetMeters = 200_000.0))

        assertEquals(RouteUnavailable.OUT_OF_RANGE, (tooShort as RouteOutcome.None).reason)
        assertEquals(RouteUnavailable.OUT_OF_RANGE, (tooLong as RouteOutcome.None).reason)
    }

    /** Claims make the planned route familiar; without them it's new ground. */
    @Test
    fun `claims are reported as familiar ground`() = runBlocking {
        val corridor = (8..16).map { point(it, 12) }
        val outcome = suggester().suggest(
            request(attempt = 0).copy(claimRings = listOf(corridor)),
        )

        val found = outcome as RouteOutcome.Found
        assertTrue(found.suggestion.familiarFraction > 0.0)
    }

    // --- fixtures ------------------------------------------------------------

    private fun suggester() = RouteSuggester(GridArea)

    private fun request(
        attempt: Int,
        pastWalks: List<Walk> = emptyList(),
    ) = RouteRequest(
        from = start,
        targetMeters = 1_600.0,
        attempt = attempt,
        pastWalks = pastWalks,
        claimRings = emptyList(),
    )

    /** A square of 400 m sides centred on [at] — a 1.6 km loop. */
    private fun squareAround(at: LatLng): List<LatLng> {
        val d = 200.0 / METERS_PER_DEGREE_LAT
        val e = 200.0 / (METERS_PER_DEGREE_LAT * 0.788)
        return listOf(
            LatLng(at.lat - d, at.lng - e),
            LatLng(at.lat - d, at.lng + e),
            LatLng(at.lat + d, at.lng + e),
            LatLng(at.lat + d, at.lng - e),
        )
    }

    private fun walk(id: String, ring: List<LatLng>) = Walk(
        id = id,
        ring = ring,
        areaSqMeters = Geo.polygonAreaSqMeters(ring),
        perimeterMeters = Geo.pathLengthMeters(ring + ring.first()),
        distanceToStartMeters = 5.0,
        closedAtEpochMs = 1_000,
        claimed = true,
    )

    /** A lattice of streets 100 m apart, wherever it is asked about. */
    private object GridArea : WalkableArea {
        override suspend fun ways(center: LatLng, radiusMeters: Double): List<WalkableWay> {
            val ways = ArrayList<WalkableWay>()
            for (y in 0..24) {
                ways.add(WalkableWay((0..24).map { x -> point(x, y) }, comfort = 1.0))
            }
            for (x in 0..24) {
                ways.add(WalkableWay((0..24).map { y -> point(x, y) }, comfort = 1.0))
            }
            return ways
        }
    }

    /** Offline, as far as anything asking is concerned. */
    private object NoArea : WalkableArea {
        override suspend fun ways(center: LatLng, radiusMeters: Double): List<WalkableWay>? = null
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
        const val STEP_LAT = 100.0 / METERS_PER_DEGREE_LAT
        const val STEP_LNG = 100.0 / (METERS_PER_DEGREE_LAT * 0.788)

        fun point(x: Int, y: Int) = LatLng(
            lat = 37.98 + y * STEP_LAT,
            lng = 23.72 + x * STEP_LNG,
        )
    }
}
