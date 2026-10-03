package io.app.enclose.export

import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An imported walk that came back round to its start must be claimable even
 * when its recorder ran on past the start — that overshoot is the commonest
 * reason a real track "doesn't close".
 */
class ImportClosureTest {

    /** ~1 m per 9e-6° of latitude near the equator; good enough for metres here. */
    private fun p(northM: Double, eastM: Double) = LatLng(northM * 9e-6, eastM * 9e-6)

    /** A 200 m square loop from (0,0), back to the start. */
    private val square = listOf(
        p(0.0, 0.0), p(0.0, 50.0), p(0.0, 100.0), p(0.0, 150.0), p(0.0, 200.0),
        p(50.0, 200.0), p(100.0, 200.0), p(150.0, 200.0), p(200.0, 200.0),
        p(200.0, 150.0), p(200.0, 100.0), p(200.0, 50.0), p(200.0, 0.0),
        p(150.0, 0.0), p(100.0, 0.0), p(50.0, 0.0), p(5.0, 0.0),
    )

    private fun end(points: List<LatLng>) = ImportClosure.endIndex(
        points,
        closeRadiusMeters = 75.0,
        leaveRadiusMeters = 40.0,
        minPerimeterMeters = 80.0,
    )

    @Test
    fun `a track that ends at its start ends there`() {
        assertEquals(square.lastIndex, end(square))
    }

    @Test
    fun `an overshoot past the start is cut at the return`() {
        // Walks on 300 m south past the start, to the car.
        val track = square + listOf(p(-50.0, 0.0), p(-150.0, 0.0), p(-300.0, 0.0))
        assertEquals(square.lastIndex, end(track))
    }

    @Test
    fun `the nearest point of the last return is chosen`() {
        val track = square.dropLast(1) + listOf(p(30.0, 0.0), p(2.0, 0.0), p(-30.0, 0.0), p(-200.0, 0.0))
        assertEquals(track.indexOf(p(2.0, 0.0)), end(track))
    }

    @Test
    fun `a second lap is kept, not cut at the first pass`() {
        val track = square + square.drop(1)
        assertEquals(track.lastIndex, end(track))
    }

    @Test
    fun `a track that never comes back can't close`() {
        val outAndAway = listOf(p(0.0, 0.0), p(0.0, 100.0), p(0.0, 200.0), p(0.0, 300.0))
        assertNull(end(outAndAway))
    }

    @Test
    fun `hovering at the start without leaving it doesn't count`() {
        val dithering = listOf(p(0.0, 0.0), p(10.0, 0.0), p(0.0, 10.0), p(10.0, 10.0), p(0.0, 0.0))
        assertNull(end(dithering))
    }

    @Test
    fun `the end gap is first point to last`() {
        val gap = ImportClosure.endGapMeters(listOf(p(0.0, 0.0), p(0.0, 100.0), p(0.0, 140.0)))
        assertEquals(140.0, gap, 2.0)
    }
}
