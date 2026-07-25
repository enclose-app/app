package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Place.groupingName] is what a claim gets filed under, so it feeds the
 * per-city coverage stats. These pin the fallback order that behaviour has
 * always relied on: city, else area, else country.
 */
class PlaceTest {

    @Test
    fun `a named city wins`() {
        val place = Place(city = "Athens", area = "Attica", country = "Greece")

        assertEquals("Athens", place.groupingName)
    }

    @Test
    fun `open country falls back to the area`() {
        val place = Place(city = null, area = "Attica", country = "Greece")

        assertEquals("Attica", place.groupingName)
    }

    @Test
    fun `with nothing but a country, the country is the grouping`() {
        val place = Place(country = "Greece")

        assertEquals("Greece", place.groupingName)
    }

    @Test
    fun `a place that names nothing groups nowhere`() {
        val place = Place()

        assertNull(place.groupingName)
        assertTrue(place.isEmpty)
    }

    @Test
    fun `any single name is enough to not be empty`() {
        assertFalse(Place(city = "Athens").isEmpty)
        assertFalse(Place(area = "Attica").isEmpty)
        assertFalse(Place(country = "Greece").isEmpty)
    }

    @Test
    fun `a country code alone still counts as nothing to show`() {
        // A bare code names no place a user would recognise, so it must not
        // suppress the "couldn't find this" message on the detail screen.
        assertTrue(Place(countryCode = "GR").isEmpty)
    }
}
