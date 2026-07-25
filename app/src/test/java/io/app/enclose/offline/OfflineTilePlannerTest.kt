package io.app.enclose.offline

import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile caching spends the user's disk and their mobile data, so the two rules
 * that bound both — the clamp on region size and which region gets thrown away
 * — are pinned here.
 */
class OfflineTilePlannerTest {

    @Test
    fun `one region per city, covering its claims`() {
        val regions = OfflineTilePlanner.plan(
            listOf(
                claim("a", city = "Athens", at = ATHENS),
                claim("b", city = "Athens", at = ATHENS.offset(0.01, 0.01)),
                claim("c", city = "Berlin", at = BERLIN),
            ),
        )

        assertEquals(setOf("Athens", "Berlin"), regions.map { it.city }.toSet())
        val athens = regions.first { it.city == "Athens" }
        assertTrue("Both Athens claims inside", OfflineTilePlanner.contains(athens, ATHENS))
        assertTrue(OfflineTilePlanner.contains(athens, ATHENS.offset(0.01, 0.01)))
    }

    @Test
    fun `a region is padded beyond the claims themselves`() {
        val regions = OfflineTilePlanner.plan(listOf(claim("a", "Athens", ATHENS)))
        val region = regions.single()

        // You approach a loop before walking it, so the streets just outside
        // must be cached too.
        val justOutside = LatLng(ATHENS.lat + 0.005, ATHENS.lng)
        assertTrue(OfflineTilePlanner.contains(region, justOutside))
    }

    @Test
    fun `claims scattered across a country are clamped to a sane box`() {
        // Athens to Berlin is ~1800 km; an unclamped box would be enormous, and
        // tile count grows with its area.
        val regions = OfflineTilePlanner.plan(
            listOf(
                claim("a", city = "Everywhere", at = ATHENS),
                claim("b", city = "Everywhere", at = BERLIN),
            ),
        )
        val region = regions.single()

        val heightMeters = Geo.distanceMeters(
            LatLng(region.southWest.lat, region.southWest.lng),
            LatLng(region.northEast.lat, region.southWest.lng),
        )
        assertTrue(
            "Height was $heightMeters m, expected <= ${OfflineTilePlanner.MAX_SPAN_METERS}",
            heightMeters <= OfflineTilePlanner.MAX_SPAN_METERS * 1.05,
        )
    }

    @Test
    fun `claims with no city yet are not cached`() {
        // Without a name there is nothing to key the region on, and the lookup
        // may still resolve — caching now could mean caching twice.
        assertTrue(OfflineTilePlanner.plan(listOf(claim("a", city = "", at = ATHENS))).isEmpty())
    }

    @Test
    fun `conquered claims stop being cached`() {
        val fallen = claim("a", "Athens", ATHENS).copy(conqueredAtEpochMs = 5L)

        assertTrue(OfflineTilePlanner.plan(listOf(fallen)).isEmpty())
    }

    @Test
    fun `nothing is evicted while under budget`() {
        val cached = listOf(region("Athens", sizeMb = 50, visits = 1))

        assertTrue(OfflineTilePlanner.evictions(cached, budgetBytes = mb(300)).isEmpty())
    }

    @Test
    fun `the least visited region is evicted first`() {
        val cached = listOf(
            region("Athens", sizeMb = 200, visits = 40),
            region("Holiday", sizeMb = 200, visits = 1),
        )

        assertEquals(
            listOf("Holiday"),
            OfflineTilePlanner.evictions(cached, budgetBytes = mb(300)),
        )
    }

    @Test
    fun `equally unused regions break the tie on the older visit`() {
        val cached = listOf(
            region("Recent", sizeMb = 200, visits = 2, lastVisited = 9_000L),
            region("Stale", sizeMb = 200, visits = 2, lastVisited = 1_000L),
        )

        assertEquals(
            listOf("Stale"),
            OfflineTilePlanner.evictions(cached, budgetBytes = mb(300)),
        )
    }

    @Test
    fun `eviction stops as soon as it is back under budget`() {
        val cached = listOf(
            region("A", sizeMb = 100, visits = 1),
            region("B", sizeMb = 100, visits = 2),
            region("C", sizeMb = 100, visits = 3),
            region("D", sizeMb = 100, visits = 4),
        )

        // 400 MB against a 300 MB budget: dropping the least used one is enough.
        assertEquals(listOf("A"), OfflineTilePlanner.evictions(cached, budgetBytes = mb(300)))
    }

    @Test
    fun `a region that is still wanted is never evicted`() {
        val cached = listOf(
            region("Home", sizeMb = 400, visits = 0),
            region("Old", sizeMb = 100, visits = 50),
        )

        // Home is huge and unvisited, but it's in the current plan — deleting it
        // would only queue an immediate re-download.
        val evicted = OfflineTilePlanner.evictions(
            cached,
            budgetBytes = mb(300),
            keep = setOf("Home"),
        )
        assertFalse(evicted.contains("Home"))
        assertEquals(listOf("Old"), evicted)
    }

    // --- helpers -------------------------------------------------------------

    private fun mb(n: Int): Long = n.toLong() * 1024 * 1024

    private fun region(
        city: String,
        sizeMb: Int,
        visits: Int,
        lastVisited: Long = 0L,
    ) = CachedRegion(city, mb(sizeMb), visits, lastVisited)

    private fun LatLng.offset(dLat: Double, dLng: Double) = LatLng(lat + dLat, lng + dLng)

    private fun claim(id: String, city: String, at: LatLng): Territory {
        val ring = listOf(at, LatLng(at.lat, at.lng + 0.001), LatLng(at.lat + 0.001, at.lng))
        return Territory(
            id = id,
            name = id,
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = 100.0,
            perimeterMeters = 100.0,
            claimedAtEpochMs = 0L,
            city = city,
        )
    }

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
        val BERLIN = LatLng(52.5200, 13.4050)
    }
}
