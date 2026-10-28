package io.app.enclose.data

import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The passport is a travel record, so ordering and what earns a stamp at all
 * are the things worth pinning.
 */
class PassportTest {

    private var placed = 0

    @Test
    fun `countries are stamped in the order they were first walked`() {
        val stamps = Passport.stamps(
            listOf(
                claim("a", city = "Berlin", country = "Germany", at = 300L),
                claim("b", city = "Athens", country = "Greece", at = 100L),
                claim("c", city = "Munich", country = "Germany", at = 200L),
            ),
        )

        // Greece first: it was walked before Germany, even though Germany has
        // more claims. A passport is chronological, not a leaderboard.
        assertEquals(listOf("Greece", "Germany"), stamps.map { it.country })
        assertEquals(100L, stamps[0].firstClaimedAtEpochMs)
        assertEquals(200L, stamps[1].firstClaimedAtEpochMs)
    }

    @Test
    fun `a stamp collects its distinct cities alphabetically`() {
        val stamps = Passport.stamps(
            listOf(
                claim("a", city = "Munich", country = "Germany", at = 1L),
                claim("b", city = "Berlin", country = "Germany", at = 2L),
                claim("c", city = "Berlin", country = "Germany", at = 3L),
            ),
        )

        assertEquals(listOf("Berlin", "Munich"), stamps.single().cities)
        assertEquals(3, stamps.single().territoryCount)
    }

    @Test
    fun `claims with no country yet earn no stamp`() {
        val stamps = Passport.stamps(
            listOf(
                claim("a", city = "Athens", country = "", at = 1L),
                claim("b", city = "", country = "  ", at = 2L),
            ),
        )

        // An unresolved lookup is not a place anyone has been.
        assertTrue(stamps.isEmpty())
    }

    @Test
    fun `a country resolved without a city still earns a stamp`() {
        val stamps = Passport.stamps(
            listOf(claim("a", city = "", country = "Iceland", at = 1L)),
        )

        assertEquals("Iceland", stamps.single().country)
        assertTrue("No city to list", stamps.single().cities.isEmpty())
        assertEquals(1, stamps.single().territoryCount)
    }

    @Test
    fun `area is summed per country`() {
        val stamps = Passport.stamps(
            listOf(
                claim("a", city = "Athens", country = "Greece", at = 1L, area = 1_000.0),
                claim("b", city = "Patras", country = "Greece", at = 2L, area = 2_500.0),
            ),
        )

        assertEquals(3_500.0, stamps.single().claimedAreaSqMeters, 0.001)
    }

    /** Claims are layers, so a loop walked twice is one place, not two. */
    @Test
    fun `ground claimed twice in a country is counted once`() {
        val first = claim("a", city = "Athens", country = "Greece", at = 1L)
        val again = first.copy(id = "b", claimedAtEpochMs = 2L)

        val stamp = Passport.stamps(listOf(first, again)).single()

        assertEquals(first.areaSqMeters, stamp.claimedAreaSqMeters, first.areaSqMeters * 0.01)
        assertEquals(2, stamp.territoryCount)
    }

    @Test
    fun `no claims yields no stamps`() {
        assertTrue(Passport.stamps(emptyList()).isEmpty())
    }

    private fun claim(
        id: String,
        city: String,
        country: String,
        at: Long,
        area: Double? = null,
    ): Territory {
        // Each claim on ground of its own, so only the test that means to
        // overlap two claims does: overlapping ones share ground (Footprint).
        val south = 37.9838 + placed++ * 0.01
        val ring = listOf(
            LatLng(south, 23.7275),
            LatLng(south, 23.7285),
            LatLng(south + 0.001, 23.7285),
        )
        return Territory(
            id = id,
            name = "Claim $id",
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = area ?: io.app.enclose.geo.Geo.polygonAreaSqMeters(ring),
            perimeterMeters = 0.0,
            claimedAtEpochMs = at,
            city = city,
            country = country,
        )
    }
}
