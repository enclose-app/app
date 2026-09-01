package io.app.enclose.geo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Point-in-ring, which is what turns a tap on the map into a claim.
 *
 * A walked loop is rarely a box — it goes round a block and back down an alley —
 * so the concave case is the ordinary one here, not the exotic one.
 */
class GeoContainsTest {

    @Test
    fun `a point inside a square is inside`() {
        assertTrue(Geo.ringContains(SQUARE, LatLng(0.5, 0.5)))
    }

    @Test
    fun `points outside are outside`() {
        assertFalse(Geo.ringContains(SQUARE, LatLng(1.5, 0.5)))
        assertFalse(Geo.ringContains(SQUARE, LatLng(0.5, -0.5)))
        // Level with the shape but east of it: the ray cast east from here
        // crosses nothing, and a ray cast the other way would cross twice.
        assertFalse(Geo.ringContains(SQUARE, LatLng(0.5, 2.0)))
    }

    @Test
    fun `the notch of a concave ring is outside it`() {
        // A "C": the gap between the arms is inside the bounding box and
        // outside the shape, which is precisely what a bbox test gets wrong.
        val c = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 3.0),
            LatLng(1.0, 3.0),
            LatLng(1.0, 1.0),
            LatLng(2.0, 1.0),
            LatLng(2.0, 3.0),
            LatLng(3.0, 3.0),
            LatLng(3.0, 0.0),
        )

        assertTrue(Geo.ringContains(c, LatLng(0.5, 2.0)))
        assertTrue(Geo.ringContains(c, LatLng(1.5, 0.5)))
        assertFalse("The notch between the arms is not enclosed", Geo.ringContains(c, LatLng(1.5, 2.0)))
    }

    @Test
    fun `the ring is treated as implicitly closed`() {
        // Only three of the four sides are written down; the closing one is
        // implied everywhere else in this app, and has to be here too.
        val triangle = listOf(LatLng(0.0, 0.0), LatLng(0.0, 2.0), LatLng(2.0, 0.0))

        assertTrue(Geo.ringContains(triangle, LatLng(0.4, 0.4)))
        assertFalse(Geo.ringContains(triangle, LatLng(1.5, 1.5)))
    }

    @Test
    fun `a degenerate ring contains nothing`() {
        assertFalse(Geo.ringContains(emptyList(), LatLng(0.0, 0.0)))
        assertFalse(Geo.ringContains(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0)), LatLng(0.5, 0.5)))
    }

    @Test
    fun `holes are not part of the polygon`() {
        val hole = listOf(
            LatLng(0.4, 0.4),
            LatLng(0.4, 0.6),
            LatLng(0.6, 0.6),
            LatLng(0.6, 0.4),
        )
        val polygon: GeoPolygon = listOf(SQUARE, hole)

        assertTrue(Geo.polygonContains(polygon, LatLng(0.2, 0.2)))
        assertFalse(Geo.polygonContains(polygon, LatLng(0.5, 0.5)))
        assertTrue(Geo.polygonsContain(listOf(polygon), LatLng(0.2, 0.2)))
        assertFalse(Geo.polygonsContain(emptyList(), LatLng(0.2, 0.2)))
    }

    private companion object {
        val SQUARE = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 1.0),
            LatLng(1.0, 1.0),
            LatLng(1.0, 0.0),
        )
    }
}
