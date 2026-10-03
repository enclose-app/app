package io.app.enclose.watch

import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.TrackingManager.WalkState
import io.app.enclose.watchlink.GeoPoint
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMapOfTest {

    private val origin = LatLng(37.42, -122.08)

    /** [n] points along a straight line north, ~1 m apart, with a little zig-zag. */
    private fun wiggle(n: Int) = (0 until n).map { i ->
        LatLng(origin.lat + i * 9e-6, origin.lng + if (i % 2 == 0) 0.0 else 4e-6)
    }

    private fun square(center: LatLng, halfDeg: Double = 0.001) = listOf(
        LatLng(center.lat - halfDeg, center.lng - halfDeg),
        LatLng(center.lat - halfDeg, center.lng + halfDeg),
        LatLng(center.lat + halfDeg, center.lng + halfDeg),
        LatLng(center.lat + halfDeg, center.lng - halfDeg),
    )

    private fun territory(id: String, center: LatLng, color: String = "#1E88A8"): Territory {
        val ring = square(center)
        return Territory(
            id = id,
            name = id,
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = 1.0,
            perimeterMeters = 1.0,
            claimedAtEpochMs = 0L,
            colorHex = color,
        )
    }

    @Test
    fun `no walk sends an empty map`() {
        assertEquals(WatchMap(), watchMapOf(WalkState(), 10.0))
    }

    @Test
    fun `a walk carries its start, position and closing radius`() {
        val path = wiggle(5)
        val map = watchMapOf(
            WalkState(isTracking = true, path = path, start = path.first(), current = path.last(), startedAtMs = 7L),
            10.0,
        )
        assertEquals(7L, map.startedAtEpochMs)
        assertEquals(GeoPoint(path.first().lat, path.first().lng), map.start)
        assertEquals(GeoPoint(path.last().lat, path.last().lng), map.current)
        assertEquals(10.0, map.closeRadiusMeters, 0.0)
    }

    @Test
    fun `a straight path is thinned`() {
        val map = watchMapOf(WalkState(isTracking = true, path = wiggle(500)), 10.0)
        assertTrue("got ${map.path.size}", map.path.size < 20)
    }

    @Test
    fun `a huge walk is coarsened to fit, never cut short`() {
        // A zig-zag wide enough that the default tolerance keeps every point.
        val path = (0 until 6_000).map { i ->
            LatLng(origin.lat + i * 9e-5, origin.lng + if (i % 2 == 0) 0.0 else 2e-4)
        }
        val map = watchMapOf(WalkState(isTracking = true, path = path), 10.0)
        assertTrue("got ${map.path.size}", map.path.size <= WatchMap.MAX_POINTS)
        assertEquals(GeoPoint(path.first().lat, path.first().lng), map.path.first())
        assertEquals(GeoPoint(path.last().lat, path.last().lng), map.path.last())
    }

    @Test
    fun `only nearby claims are sent, nearest first`() {
        val near = territory("near", LatLng(origin.lat + 0.005, origin.lng))
        val nearer = territory("nearer", LatLng(origin.lat + 0.002, origin.lng), color = "#7B1FA2")
        val far = territory("far", LatLng(origin.lat + 1.0, origin.lng))
        val claims = watchClaimsNear(listOf(near, far, nearer), origin)
        assertEquals(2, claims.claims.size)
        assertEquals(0xFF7B1FA2.toInt(), claims.claims.first().color)
    }

    @Test
    fun `the number of claims is capped`() {
        val many = (0 until WatchClaims.MAX_CLAIMS + 10).map {
            territory("t$it", LatLng(origin.lat + it * 1e-4, origin.lng))
        }
        assertEquals(WatchClaims.MAX_CLAIMS, watchClaimsNear(many, origin).claims.size)
    }

    @Test
    fun `colours parse with or without alpha, and fall back when unreadable`() {
        assertEquals(0xFF1E88A8.toInt(), argbOf("#1E88A8"))
        assertEquals(0x801E88A8.toInt(), argbOf("#801E88A8"))
        assertEquals(argbOf(Territory.DEFAULT_COLOR), argbOf("purple"))
    }
}
