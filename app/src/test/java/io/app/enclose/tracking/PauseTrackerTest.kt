package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moving time decides what pace the user is shown, so the rules for "stopped"
 * are pinned here — especially that it still works with the activity permission
 * denied, which is the common case.
 */
class PauseTrackerTest {

    private val tracker = PauseTracker()

    @Test
    fun `the first fix credits nothing`() {
        // There is no interval yet — only a second fix defines one.
        tracker.update(0L, speedMps = 1.5, motion = null)

        assertEquals(0L, tracker.movingMs)
    }

    @Test
    fun `walking pace credits the whole interval`() {
        tracker.update(0L, 1.4, null)
        tracker.update(3_000L, 1.4, null)
        tracker.update(6_000L, 1.4, null)

        assertEquals(6_000L, tracker.movingMs)
    }

    @Test
    fun `standing still credits nothing, on speed alone`() {
        // No motion sample at all: the permission-denied case.
        tracker.update(0L, 0.0, null)
        tracker.update(3_000L, 0.1, null)
        tracker.update(6_000L, 0.0, null)

        assertEquals(0L, tracker.movingMs)
    }

    @Test
    fun `a stop in the middle is excluded but the walking around it is not`() {
        tracker.update(0L, 1.4, null)
        tracker.update(3_000L, 1.4, null) // +3s moving
        tracker.update(6_000L, 0.0, null) // stopped
        tracker.update(9_000L, 0.0, null) // still stopped
        tracker.update(12_000L, 1.4, null) // +3s moving again

        assertEquals(6_000L, tracker.movingMs)
    }

    @Test
    fun `a confident STILL classification stops the clock despite reported speed`() {
        val still = MotionSample(
            activity = MotionActivity.STILL,
            confidence = 90,
            atElapsedMs = 3_000L,
        )
        tracker.update(0L, 1.4, null)
        // GPS drift can report movement while standing; the classifier knows better.
        tracker.update(3_000L, 1.4, still)

        assertEquals(0L, tracker.movingMs)
    }

    @Test
    fun `a stale STILL classification is ignored`() {
        val old = MotionSample(
            activity = MotionActivity.STILL,
            confidence = 90,
            atElapsedMs = 0L,
        )
        tracker.update(100_000L, 1.4, null)
        // Far older than ACTIVITY_MAX_AGE_MS: it describes a different moment.
        tracker.update(103_000L, 1.4, old)

        assertEquals(3_000L, tracker.movingMs)
    }

    @Test
    fun `a low-confidence STILL reading does not stop the clock`() {
        val unsure = MotionSample(
            activity = MotionActivity.STILL,
            confidence = 20,
            atElapsedMs = 3_000L,
        )
        tracker.update(0L, 1.4, null)
        tracker.update(3_000L, 1.4, unsure)

        assertEquals(3_000L, tracker.movingMs)
    }

    @Test
    fun `a long recording gap is credited to neither moving nor stopped`() {
        tracker.update(0L, 1.4, null)
        // Screen off, process killed, GPS lost — whatever happened, nobody saw
        // it, and inventing an answer either way would be a lie.
        tracker.update(600_000L, 1.4, null)

        assertEquals(0L, tracker.movingMs)
    }

    @Test
    fun `time never runs backwards`() {
        tracker.update(10_000L, 1.4, null)
        tracker.update(5_000L, 1.4, null)

        assertEquals(0L, tracker.movingMs)
    }

    @Test
    fun `reset resumes from a stored total without crediting the gap`() {
        tracker.reset(movingMs = 60_000L)
        tracker.update(500_000L, 1.4, null)

        assertEquals("The first fix after a resume defines no interval", 60_000L, tracker.movingMs)

        tracker.update(503_000L, 1.4, null)
        assertEquals(63_000L, tracker.movingMs)
    }
}
