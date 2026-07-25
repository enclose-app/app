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

    @Test
    fun `no claims yields no stamps`() {
        assertTrue(Passport.stamps(emptyList()).isEmpty())
    }

    private fun claim(
        id: String,
        city: String,
        country: String,
        at: Long,
        area: Double = 100.0,
    ): Territory {
        val ring = listOf(
            LatLng(37.9838, 23.7275),
            LatLng(37.9838, 23.7285),
            LatLng(37.9848, 23.7285),
        )
        return Territory(
            id = id,
            name = "Claim $id",
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = area,
            perimeterMeters = 0.0,
            claimedAtEpochMs = at,
            city = city,
            country = country,
        )
    }
}
