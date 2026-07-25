package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resuming a walk that outlived its process. Everything except the path is
 * recomputed, so these pin that the rebuilt state agrees with the points it was
 * rebuilt from — a walk that comes back thinking it never left the start would
 * be unclosable, and one that comes back "ready to close" would hand out a
 * claim nobody was standing in.
 */
class TrackingManagerRestoreTest {

    @After
    fun clearWalk() {
        // TrackingManager is a singleton object; don't leak state across tests.
        TrackingManager.cancelWalk()
    }

    @Test
    fun `restoring rebuilds distance and the path`() {
        val path = line(pointCount = 5, spacingMeters = 100.0)

        assertTrue(TrackingManager.restore(path, startedAtMs = 42L, ActivityType.RUN))

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertEquals(path, state.path)
        assertEquals(path.first(), state.start)
        assertEquals(path.last(), state.current)
        assertEquals(42L, state.startedAtMs)
        assertEquals(ActivityType.RUN, state.activityType)
        assertEquals(Geo.pathLengthMeters(path), state.distanceMeters, 1.0)
    }

    @Test
    fun `a walk that had left the start comes back knowing it`() {
        // Well beyond LEAVE_START_RADIUS_METERS and MIN_PERIMETER_METERS.
        val path = line(pointCount = 20, spacingMeters = 30.0)

        TrackingManager.restore(path, startedAtMs = 1L, ActivityType.WALK)

        val state = TrackingManager.walk.value
        assertTrue("Should know it left the start zone", state.hasLeftStart)
        assertTrue("Long enough to be claimable", state.canCloseLoop)
    }

    @Test
    fun `a short walk comes back still unable to close`() {
        val path = line(pointCount = 3, spacingMeters = 5.0)

        TrackingManager.restore(path, startedAtMs = 1L, ActivityType.WALK)

        val state = TrackingManager.walk.value
        assertFalse(state.hasLeftStart)
        assertFalse(state.canCloseLoop)
    }

    @Test
    fun `a restored walk is never immediately closable`() {
        // A loop that was closable when the process died: back at the start
        // after a long way round.
        val out = line(pointCount = 20, spacingMeters = 30.0)
        val loop = out + out.reversed()

        TrackingManager.restore(loop, startedAtMs = 1L, ActivityType.WALK)

        val state = TrackingManager.walk.value
        assertTrue("The loop itself is long enough", state.canCloseLoop)
        assertFalse(
            "The last point proves where the walker was, not where they are",
            state.readyToClose,
        )
    }

    @Test
    fun `an empty path is refused and changes nothing`() {
        assertFalse(TrackingManager.restore(emptyList(), startedAtMs = 1L, ActivityType.WALK))
        assertFalse(TrackingManager.walk.value.isTracking)
    }

    @Test
    fun `a restored walk records the next fix`() {
        val path = line(pointCount = 5, spacingMeters = 100.0)
        TrackingManager.restore(path, startedAtMs = 1L, ActivityType.WALK)

        // No timestamp: the motion checks are skipped, as with tapped points.
        TrackingManager.onLocation(north(path.last(), 100.0))

        assertEquals(
            "The fix after a restore must extend the restored path",
            path.size + 1,
            TrackingManager.walk.value.path.size,
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun line(pointCount: Int, spacingMeters: Double): List<LatLng> =
        (0 until pointCount).map { north(ATHENS, it * spacingMeters) }

    /** [meters] north of [from]; 1 degree of latitude ≈ 111_195 m. */
    private fun north(from: LatLng, meters: Double) =
        LatLng(from.lat + meters / 111_195.0, from.lng)

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
