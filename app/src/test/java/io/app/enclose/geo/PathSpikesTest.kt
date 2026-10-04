package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The cleanup a recorded loop gets before it is claimed. What it removes is gone
 * from the claim, so the cases that must survive matter as much as the spikes.
 */
class PathSpikesTest {

    @Test
    fun `a single out-and-back fix is removed`() {
        val path = listOf(p(0.0, 0.0), p(0.0, 20.0), p(300.0, 25.0), p(0.0, 30.0), p(0.0, 50.0))

        assertEquals(
            listOf(p(0.0, 0.0), p(0.0, 20.0), p(0.0, 30.0), p(0.0, 50.0)),
            PathSpikes.remove(path),
        )
    }

    @Test
    fun `a two-fix spike is removed`() {
        val path = listOf(p(0.0, 0.0), p(0.0, 20.0), p(250.0, 22.0), p(260.0, 28.0), p(0.0, 30.0), p(0.0, 50.0))

        assertEquals(
            listOf(p(0.0, 0.0), p(0.0, 20.0), p(0.0, 30.0), p(0.0, 50.0)),
            PathSpikes.remove(path),
        )
    }

    @Test
    fun `spikes next to each other are all removed`() {
        val path = listOf(p(0.0, 0.0), p(0.0, 10.0), p(200.0, 12.0), p(0.0, 14.0), p(-200.0, 16.0), p(0.0, 18.0))

        assertEquals(
            listOf(p(0.0, 0.0), p(0.0, 10.0), p(0.0, 14.0), p(0.0, 18.0)),
            PathSpikes.remove(path),
        )
    }

    /** A corner has a long base; it is the shape of the walk, not a fault in it. */
    @Test
    fun `a real corner survives`() {
        val path = listOf(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 200.0))

        assertSame(path, PathSpikes.remove(path))
    }

    /** Walked out and back, recorded as it was walked: too many points to be a fault. */
    @Test
    fun `a walked dead end survives`() {
        val out = (0..20).map { p(it * 5.0, 0.0) }
        val path = out + out.reversed().drop(1).map { p(it.lat, it.lng + 0.00002) }

        assertSame(path, PathSpikes.remove(path))
    }

    @Test
    fun `wander within GPS noise is left alone`() {
        val path = listOf(p(0.0, 0.0), p(0.0, 10.0), p(30.0, 12.0), p(0.0, 14.0), p(0.0, 30.0))

        assertSame(path, PathSpikes.remove(path))
    }

    @Test
    fun `the start and the closing fix are never removed`() {
        val startsOut = listOf(p(300.0, 0.0), p(0.0, 0.0), p(0.0, 20.0))
        val endsOut = listOf(p(0.0, 0.0), p(0.0, 20.0), p(300.0, 20.0))

        assertSame(startsOut, PathSpikes.remove(startsOut))
        assertSame(endsOut, PathSpikes.remove(endsOut))
    }

    /** [north] and [east] metres from a fixed origin. */
    private fun p(north: Double, east: Double) =
        LatLng(ORIGIN.lat + north / 111_195.0, ORIGIN.lng + east / (111_195.0 * 0.788))

    private companion object {
        val ORIGIN = LatLng(37.9838, 23.7275)
    }
}
