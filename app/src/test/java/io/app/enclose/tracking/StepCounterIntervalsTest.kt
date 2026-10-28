package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning a hardware step total into intervals. A car journey produces no
 * readings at all, so what these pin is that the heartbeat turns that silence
 * into *zero steps* — evidence — and that nothing counted is ever lost on the
 * way.
 */
class StepCounterIntervalsTest {

    private val intervals = StepCounterIntervals(StepSource.PHONE)

    @Test
    fun `nothing is reported before the counter has been read`() {
        assertNull(intervals.report(atEpochMs = 10_000L))
    }

    @Test
    fun `the first reading is the baseline, not steps taken`() {
        // Steps since boot: none of them were taken on this walk.
        intervals.onReading(rawTotal = 52_000, atEpochMs = 1_000L)

        assertEquals(StepInterval(StepSource.PHONE, 1_000L, 10_000L, 0), intervals.report(10_000L))
    }

    @Test
    fun `each report carries the steps since the one before`() {
        intervals.onReading(52_000, 0L)
        intervals.onReading(52_014, 8_000L)
        assertEquals(StepInterval(StepSource.PHONE, 0L, 10_000L, 14), intervals.report(10_000L))

        intervals.onReading(52_030, 15_000L)
        assertEquals(StepInterval(StepSource.PHONE, 10_000L, 20_000L, 16), intervals.report(20_000L))
    }

    @Test
    fun `a stretch with no steps is reported as zero, back to back`() {
        intervals.onReading(52_000, 0L)

        val first = intervals.report(10_000L)
        val second = intervals.report(20_000L)

        assertEquals(0, first?.steps)
        assertEquals(0, second?.steps)
        assertEquals("Each starts where the last ended", first?.toEpochMs, second?.fromEpochMs)
    }

    @Test
    fun `no time passed means nothing to report`() {
        intervals.onReading(52_000, 0L)
        intervals.report(10_000L)

        assertNull(intervals.report(10_000L))
        assertNull(intervals.report(9_000L))
    }

    @Test
    fun `a counter that starts again from zero loses no steps`() {
        intervals.onReading(52_000, 0L)
        intervals.onReading(52_010, 4_000L)
        // A sensor stack restart: the count begins again from zero.
        intervals.onReading(3, 8_000L)
        assertEquals(13, intervals.report(10_000L)?.steps)

        intervals.onReading(20, 15_000L)
        assertEquals(17, intervals.report(20_000L)?.steps)
    }

    @Test
    fun `the source is carried on every interval`() {
        val watch = StepCounterIntervals(StepSource.WATCH)
        watch.onReading(0, 0L)

        assertEquals(StepSource.WATCH, watch.report(1_000L)?.source)
    }
}
