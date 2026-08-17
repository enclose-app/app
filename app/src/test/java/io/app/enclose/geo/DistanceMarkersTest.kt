package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kilometre ticks along a walked path. The property under test is that a marker
 * sits at the kilometre, not at the fix nearest it: the fixes are wherever the
 * GPS happened to land, and a marker that just picks one drifts by that much —
 * unboundedly so across a stretch where the signal dropped and the path is one
 * long straight segment.
 */
class DistanceMarkersTest {

    /** ~111.32 m per 0.001° of longitude at the equator; used to build fixtures. */
    private fun eastward(steps: Int, stepDegrees: Double = 0.001): List<LatLng> =
        (0..steps).map { LatLng(0.0, it * stepDegrees) }

    @Test
    fun `a path shorter than the spacing has no markers`() {
        val short = eastward(steps = 3) // ~334 m

        assertTrue(DistanceMarkers.along(short).isEmpty())
    }

    @Test
    fun `fewer than two points has no markers`() {
        assertTrue(DistanceMarkers.along(emptyList()).isEmpty())
        assertTrue(DistanceMarkers.along(listOf(LatLng(52.5, 13.4))).isEmpty())
    }

    @Test
    fun `markers land at whole multiples of the spacing along the path`() {
        val path = eastward(steps = 40) // ~4.45 km

        val markers = DistanceMarkers.along(path, spacingMeters = 1000.0)

        assertEquals(4, markers.size)
        markers.forEachIndexed { i, marker ->
            assertEquals(i + 1, marker.index)
            assertEquals((i + 1) * 1000.0, marker.distanceMeters, 0.0001)
            // The interpolated point really is that far along the walked line.
            val walkedTo = Geo.pathLengthMeters(pathUpTo(path, marker.position))
            assertEquals(marker.distanceMeters, walkedTo, 1.0)
        }
    }

    /**
     * The signal-gap case: two fixes 3 km apart, bridged by a straight line. Each
     * kilometre on it still gets its own marker — dropping to one per segment
     * would leave the longest stretch of the walk unmarked.
     */
    @Test
    fun `one long segment carries every marker that falls on it`() {
        val gapped = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.03)) // ~3.34 km

        val markers = DistanceMarkers.along(gapped, spacingMeters = 1000.0)

        assertEquals(3, markers.size)
        assertEquals(listOf(1, 2, 3), markers.map { it.index })
        // Evenly spaced along the one segment, not bunched at either end.
        markers.forEach { assertEquals(0.0, it.position.lat, 1e-9) }
        assertTrue(markers[0].position.lng < markers[1].position.lng)
        assertTrue(markers[1].position.lng < markers[2].position.lng)
    }

    /** Repeated fixes (standing still) are zero-length segments, not divisions by zero. */
    @Test
    fun `duplicate points are skipped`() {
        val stalled = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0)) +
            eastward(steps = 20).map { LatLng(it.lat, it.lng) } +
            List(3) { LatLng(0.0, 0.02) }

        val markers = DistanceMarkers.along(stalled, spacingMeters = 1000.0)

        assertEquals(2, markers.size)
        markers.forEach { assertTrue(it.position.lng.isFinite()) }
    }

    @Test
    fun `spacing is honoured when it is not a kilometre`() {
        val path = eastward(steps = 20) // ~2.23 km

        val markers = DistanceMarkers.along(path, spacingMeters = 500.0)

        assertEquals(4, markers.size)
        assertEquals(2000.0, markers.last().distanceMeters, 0.0001)
    }

    @Test
    fun `a non-positive spacing yields nothing rather than looping forever`() {
        val path = eastward(steps = 40)

        assertTrue(DistanceMarkers.along(path, spacingMeters = 0.0).isEmpty())
        assertTrue(DistanceMarkers.along(path, spacingMeters = -100.0).isEmpty())
    }

    /** The cap protects the map, so it has to bite before the geometry runs away. */
    @Test
    fun `the marker count is capped`() {
        val path = eastward(steps = 100)

        val markers = DistanceMarkers.along(path, spacingMeters = 1.0, limit = 10)

        assertEquals(10, markers.size)
        assertEquals(10, markers.last().index)
    }

    /** Distance along [path] up to the first point at or past [target]'s longitude. */
    private fun pathUpTo(path: List<LatLng>, target: LatLng): List<LatLng> =
        path.takeWhile { it.lng < target.lng } + target
}
