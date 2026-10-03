package io.app.enclose.export

import io.app.enclose.data.VoidedWalk
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voided walk shared as GPX is the user's only way to take it elsewhere, so
 * it has to come back as exactly the walk it was — and stay open, since it
 * never closed a loop.
 */
class VoidedWalkGpxTest {

    private val walk = VoidedWalk(
        id = "v1",
        path = listOf(LatLng(37.9838, 23.7275), LatLng(37.985, 23.73), LatLng(37.9861, 23.7312)),
        startedAtEpochMs = 1_700_300_000_000,
        voidedAtEpochMs = 1_700_300_600_000,
        distanceMeters = 420.0,
        reason = VoidedWalk.Reason.VEHICLE,
    )

    @Test
    fun `the track reads back as the same points, in order`() {
        val points = GpxImporter.parse(GeoExporter.toGpx(walk)).map { it.position }
        assertEquals(walk.path, points)
    }

    @Test
    fun `the track is left open`() {
        val points = GpxImporter.parse(GeoExporter.toGpx(walk)).map { it.position }
        assertTrue(points.first() != points.last())
    }

    @Test
    fun `the file name is dated by the walk's start`() {
        assertTrue(GeoExporter.safeFileName(walk).startsWith("enclose-walk-2023-11-"))
    }
}
