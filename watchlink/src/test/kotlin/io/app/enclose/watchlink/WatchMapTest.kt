package io.app.enclose.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WatchMapTest {

    private val a = GeoPoint(37.4219983, -122.0840575)
    private val b = GeoPoint(37.4221, -122.0838)
    private val c = GeoPoint(37.4223, -122.0841)

    @Test
    fun `a walk survives the round trip to the centimetre`() {
        val map = WatchMap(
            startedAtEpochMs = 5L,
            path = listOf(a, b, c),
            start = a,
            current = c,
            closeRadiusMeters = 10.0,
        )
        val fields = map.toFields()
        assertEquals(map, WatchMap.fromFields { fields[it] })
    }

    @Test
    fun `an empty walk survives the round trip`() {
        val fields = WatchMap().toFields()
        assertEquals(WatchMap(), WatchMap.fromFields { fields[it] })
    }

    @Test
    fun `mismatched coordinate arrays are unreadable`() {
        val fields = WatchMap(path = listOf(a, b)).toFields() + ("lngs" to longArrayOf(1))
        assertNull(WatchMap.fromFields { fields[it] })
    }

    @Test
    fun `claims with holes and several polygons survive the round trip`() {
        val claims = WatchClaims(
            listOf(
                WatchClaims.Claim(
                    color = 0xFF7B1FA2.toInt(),
                    polygons = listOf(
                        listOf(listOf(a, b, c, a), listOf(b, c, b)),
                        listOf(listOf(c, a, b, c)),
                    ),
                ),
                WatchClaims.Claim(color = 0xFF1E88A8.toInt(), polygons = listOf(listOf(listOf(a, c, b, a)))),
            ),
        )
        val fields = claims.toFields()
        assertEquals(claims, WatchClaims.fromFields { fields[it] })
    }

    @Test
    fun `claim counts that don't add up are unreadable`() {
        val fields = WatchClaims(
            listOf(WatchClaims.Claim(0, listOf(listOf(listOf(a, b, c))))),
        ).toFields() + ("points" to longArrayOf(4))
        assertNull(WatchClaims.fromFields { fields[it] })
    }

    @Test
    fun `a circle is closed and the right size`() {
        val ring = WatchGeo.circle(a, radiusMeters = 10.0)
        assertEquals(ring.first(), ring.last())
        // 10 m north is about 9e-5 degrees of latitude.
        val north = ring.maxOf { it.lat } - a.lat
        assertTrue(abs(north - 8.99e-5) < 1e-6)
    }
}
