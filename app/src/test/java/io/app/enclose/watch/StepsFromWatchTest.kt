package io.app.enclose.watch

import io.app.enclose.tracking.StepInterval
import io.app.enclose.tracking.StepSource
import io.app.enclose.tracking.TrackingManager.WalkState
import io.app.enclose.watchlink.WatchSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The watch's steps are evidence only for the walk they were counted on. */
class StepsFromWatchTest {

    private val start = 1_700_000_000_000L
    private val steps = WatchSteps(
        startedAtEpochMs = start,
        intervals = listOf(
            WatchSteps.Interval(start, start + 60_000L, 104),
            WatchSteps.Interval(start + 60_000L, start + 120_000L, 98),
        ),
    )

    @Test
    fun `steps for the walk running are the watch's evidence for it`() {
        assertEquals(
            listOf(
                StepInterval(StepSource.WATCH, start, start + 60_000L, 104),
                StepInterval(StepSource.WATCH, start + 60_000L, start + 120_000L, 98),
            ),
            stepsFromWatch(WalkState(isTracking = true, startedAtMs = start), steps),
        )
    }

    @Test
    fun `steps for another walk are not`() {
        assertNull(stepsFromWatch(WalkState(isTracking = true, startedAtMs = start + 3_600_000L), steps))
    }

    @Test
    fun `steps before the walk has a start can't be matched to it`() {
        assertNull(stepsFromWatch(WalkState(isTracking = true), steps))
    }

    @Test
    fun `steps with no walk running are not`() {
        assertNull(stepsFromWatch(WalkState(isTracking = false, startedAtMs = start), steps))
    }
}
