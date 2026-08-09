package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile arithmetic. Wrong here and the planner fetches the roads of somewhere
 * else entirely — which looks, on screen, exactly like "no route found".
 */
class SlippyTileTest {

    /** Athens at zoom 14, against the numbering every tile server uses. */
    @Test
    fun `a known point lands in the known tile`() {
        val tile = SlippyTile.of(LatLng(37.9838, 23.7275), 14)

        assertEquals(14, tile.z)
        assertEquals(9271, tile.x)
        assertEquals(6320, tile.y)
    }

    /** The y axis runs north to south — the one part of this that trips people. */
    @Test
    fun `y grows southwards`() {
        val north = SlippyTile.of(LatLng(52.0, 13.0), 12)
        val south = SlippyTile.of(LatLng(48.0, 13.0), 12)

        assertTrue(south.y > north.y)
    }

    @Test
    fun `a point round trips through its own tile coordinates`() {
        val point = LatLng(37.9838, 23.7275)
        val tile = SlippyTile.of(point, 13)

        // Find the tile-local position by projecting back and forth: the corner
        // and the far corner bracket the point, so a bisection is unnecessary —
        // this checks the two directions agree at the corners themselves.
        val corner = SlippyTile.toLatLng(tile, 0.0, 0.0, 4096)
        val far = SlippyTile.toLatLng(tile, 4096.0, 4096.0, 4096)

        assertTrue(corner.lat > point.lat && point.lat > far.lat)
        assertTrue(corner.lng < point.lng && point.lng < far.lng)
        assertEquals(tile, SlippyTile.of(corner, 13))
    }

    @Test
    fun `cover returns every tile touching the box`() {
        val center = LatLng(37.9838, 23.7275)
        val small = SlippyTile.cover(GeoBounds.around(center, 100.0), 13)
        assertEquals(listOf(SlippyTile.of(center, 13)), small)

        // Three kilometres each way is more than one zoom-13 tile at this
        // latitude, so it has to span several.
        val wide = SlippyTile.cover(GeoBounds.around(center, 3_000.0), 13)
        assertTrue(wide.size in 4..9)
        assertTrue(wide.contains(SlippyTile.of(center, 13)))
        assertEquals(wide.size, wide.distinct().size)
    }

    /**
     * A degree of longitude is shorter than a degree of latitude everywhere but
     * the equator, so the box has to be wider in degrees to be square on the
     * ground. Without this the planner fetches half the ground it asked for at
     * northern latitudes.
     */
    @Test
    fun `the search box is square on the ground, not in degrees`() {
        val center = LatLng(60.0, 10.0)
        val bounds = GeoBounds.around(center, 1_000.0)

        val northSouth = Geo.distanceMeters(
            LatLng(bounds.south, center.lng),
            LatLng(bounds.north, center.lng),
        )
        val eastWest = Geo.distanceMeters(
            LatLng(center.lat, bounds.west),
            LatLng(center.lat, bounds.east),
        )

        assertEquals(2_000.0, northSouth, 20.0)
        assertEquals(2_000.0, eastWest, 20.0)
    }

    @Test
    fun `tile span shrinks towards the poles`() {
        assertTrue(
            SlippyTile.tileSpanMeters(13, 60.0) < SlippyTile.tileSpanMeters(13, 0.0),
        )
        // Zoom 13 at the equator: the world in 8192 columns.
        assertEquals(40_075_016.686 / 8192, SlippyTile.tileSpanMeters(13, 0.0), 1.0)
    }
}
