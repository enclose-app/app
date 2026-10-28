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

    private fun territory(
        id: String,
        center: LatLng,
        color: String = "#1E88A8",
        claimedAt: Long = 0L,
    ): Territory {
        val ring = square(center)
        return Territory(
            id = id,
            name = id,
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = 1.0,
            perimeterMeters = 1.0,
            claimedAtEpochMs = claimedAt,
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
    fun `only nearby claims are sent`() {
        val near = territory("near", LatLng(origin.lat + 0.005, origin.lng), color = "#7B1FA2")
        val far = territory("far", LatLng(origin.lat + 1.0, origin.lng))
        val claims = watchClaimsNear(listOf(near, far), origin)
        assertEquals(listOf(0xFF7B1FA2.toInt()), claims.claims.map { it.color })
    }

    /** The watch draws claims in the order they arrive, and the newest goes on top. */
    @Test
    fun `nearby claims are sent oldest first`() {
        val newer = territory("newer", LatLng(origin.lat + 0.002, origin.lng), color = "#7B1FA2", claimedAt = 2L)
        val older = territory("older", LatLng(origin.lat + 0.005, origin.lng), color = "#1E88A8", claimedAt = 1L)
        val claims = watchClaimsNear(listOf(newer, older), origin)
        assertEquals(listOf(0xFF1E88A8.toInt(), 0xFF7B1FA2.toInt()), claims.claims.map { it.color })
    }

    /** Nearest first decides who makes the cut, even though the order sent is by age. */
    @Test
    fun `the cap keeps the nearest claims`() {
        val far = territory("far", LatLng(origin.lat + 0.03, origin.lng), color = "#7B1FA2", claimedAt = 0L)
        val near = (0 until WatchClaims.MAX_CLAIMS).map {
            territory("t$it", LatLng(origin.lat + it * 1e-4, origin.lng), claimedAt = 10L + it)
        }
        val sent = watchClaimsNear(listOf(far) + near, origin).claims
        assertEquals(WatchClaims.MAX_CLAIMS, sent.size)
        assertTrue("The farthest claim is the one left out", sent.none { it.color == 0xFF7B1FA2.toInt() })
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
