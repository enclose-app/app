package io.app.enclose.data

import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-city coverage is the headline number on the profile screen, so what it
 * groups and what it divides by are pinned here — above all that a second city
 * can't drag the first one's percentage down.
 */
class CoverageTest {

    @Test
    fun `claims are grouped by city, biggest first`() {
        val coverage = Coverage.byCity(
            listOf(
                square(city = "Athens", at = ATHENS, sizeDeg = 0.004),
                square(city = "Athens", at = ATHENS.offset(0.02, 0.02), sizeDeg = 0.004),
                square(city = "Berlin", at = BERLIN, sizeDeg = 0.002),
            ),
        )

        assertEquals(listOf("Athens", "Berlin"), coverage.map { it.city })
        assertEquals(2, coverage[0].territoryCount)
        assertEquals(1, coverage[1].territoryCount)
    }

    @Test
    fun `a claim filling its own box reads as fully covered`() {
        // One square claim: its bounding box is the claim, so it's 100%.
        val coverage = Coverage.byCity(listOf(square("Athens", ATHENS, sizeDeg = 0.004)))

        assertEquals(1, coverage.size)
        assertEquals(100.0, coverage[0].percent, 0.5)
    }

    @Test
    fun `walking a second city does not dilute the first`() {
        val athensOnly = Coverage.byCity(listOf(square("Athens", ATHENS, 0.004)))
        val bothCities = Coverage.byCity(
            listOf(
                square("Athens", ATHENS, 0.004),
                square("Berlin", BERLIN, 0.004),
            ),
        )

        val athensAfter = bothCities.first { it.city == "Athens" }
        assertEquals(athensOnly[0].percent, athensAfter.percent, 0.001)
        assertTrue("A city measured on its own box stays meaningful", athensAfter.percent > 50.0)
    }

    @Test
    fun `sparse claims across a city cover little of it`() {
        // Two small claims at opposite corners of a wide box.
        val coverage = Coverage.byCity(
            listOf(
                square("Athens", ATHENS, sizeDeg = 0.001),
                square("Athens", ATHENS.offset(0.05, 0.05), sizeDeg = 0.001),
            ),
        )

        assertTrue("Expected sparse coverage, got ${coverage[0].percent}%", coverage[0].percent < 5.0)
    }

    @Test
    fun `unresolved claims group together and are reported as unknown`() {
        val coverage = Coverage.byCity(
            listOf(
                square(city = "", at = ATHENS, sizeDeg = 0.004),
                square(city = "  ", at = ATHENS.offset(0.01, 0.01), sizeDeg = 0.004),
            ),
        )

        assertEquals(1, coverage.size)
        assertTrue(coverage[0].isUnknown)
        assertEquals(2, coverage[0].territoryCount)
        assertEquals(CityCoverage.UNKNOWN_CITY, coverage[0].displayName)
    }

    @Test
    fun `no claims yields no cities`() {
        assertTrue(Coverage.byCity(emptyList()).isEmpty())
    }

    @Test
    fun `a degenerate claim reports zero rather than dividing by zero`() {
        val straightLine = territory(
            city = "Athens",
            ring = listOf(ATHENS, ATHENS.offset(0.0, 0.001), ATHENS.offset(0.0, 0.002)),
            areaSqMeters = 0.0,
        )

        assertEquals(0.0, Coverage.byCity(listOf(straightLine))[0].percent, 0.0)
    }

    // --- helpers -------------------------------------------------------------

    private fun LatLng.offset(dLat: Double, dLng: Double) = LatLng(lat + dLat, lng + dLng)

    /** A square claim whose stored area matches its ring, as a real claim's does. */
    private fun square(city: String, at: LatLng, sizeDeg: Double): Territory {
        val ring = listOf(
            at,
            LatLng(at.lat, at.lng + sizeDeg),
            LatLng(at.lat + sizeDeg, at.lng + sizeDeg),
            LatLng(at.lat + sizeDeg, at.lng),
        )
        return territory(city, ring, io.app.enclose.geo.Geo.polygonAreaSqMeters(ring))
    }

    private fun territory(city: String, ring: List<LatLng>, areaSqMeters: Double) = Territory(
        id = "$city-${ring.first().lat}-${ring.first().lng}",
        name = "Claim",
        ring = ring,
        polygons = Territory.polygonsFromRing(ring),
        areaSqMeters = areaSqMeters,
        perimeterMeters = 0.0,
        claimedAtEpochMs = 0L,
        city = city,
    )

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
        val BERLIN = LatLng(52.5200, 13.4050)
    }
}
