package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Elevation gain is the stat people compare, and GPS altitude is noisy enough
 * that a naive sum would make a flat park lap look like a hill climb. These pin
 * the noise gate.
 */
class ElevationAccumulatorTest {

    private val accumulator = ElevationAccumulator()

    @Test
    fun `a flat walk with noisy readings gains nothing`() {
        // ±2 m of drift around a constant altitude, which is ordinary GPS noise.
        listOf(100.0, 101.5, 99.0, 100.8, 98.5, 101.0, 100.0).forEach(accumulator::add)

        assertEquals(0.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `a sustained climb is counted`() {
        listOf(100.0, 110.0, 120.0, 130.0).forEach(accumulator::add)

        assertEquals(30.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `descent is not subtracted from the total`() {
        listOf(100.0, 150.0, 100.0).forEach(accumulator::add)

        assertEquals("Gain is climb, not net change", 50.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `climbing the same hill twice counts twice`() {
        listOf(100.0, 150.0, 100.0, 150.0).forEach(accumulator::add)

        assertEquals(100.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `the first reading only sets the reference`() {
        accumulator.add(500.0)

        assertEquals(0.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `missing and unusable altitudes are ignored`() {
        accumulator.add(100.0)
        accumulator.add(null)
        accumulator.add(Double.NaN)
        accumulator.add(Double.POSITIVE_INFINITY)
        accumulator.add(110.0)

        assertEquals("Gaps must not break the running reference", 10.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `a slow climb is credited in whole steps, rounding down`() {
        // Steps of 2 m against a 3 m gate: the reference holds at 100 until 104
        // clears it (+4), then 106 is only 2 above the new reference and waits.
        // The 2 m residual is deliberately never credited — under-reporting is
        // the price of not inflating flat ground, and it's the price we chose.
        listOf(100.0, 102.0, 104.0, 106.0).forEach(accumulator::add)

        assertEquals(4.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `reset resumes from a stored total`() {
        accumulator.add(100.0)
        accumulator.add(120.0)
        accumulator.reset(gainMeters = 75.0)

        assertEquals(75.0, accumulator.gainMeters, 0.001)

        // The reference is cleared too, so the next reading can't be read as a
        // huge jump from wherever the previous walk ended.
        accumulator.add(1000.0)
        assertEquals(75.0, accumulator.gainMeters, 0.001)
    }
}
