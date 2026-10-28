package io.app.enclose.tracking

import io.app.enclose.geo.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's steps as the walk receives them: late, in batches, mostly
 * repeats of what it sent before — and able only to help.
 */
class TrackingManagerStepsTest {

    @After
    fun clearWalk() {
        TrackingManager.cancelWalk()
        TrackingManager.clearPending()
    }

    /** The phone in a bag on a jog: it counted nothing, and the watch counted every step. */
    @Test
    fun `watch steps arriving late vouch for what the phone counted nothing across`() {
        startWalking()
        go(seconds = 60)
        go(seconds = 120, mps = 3.5) // past walking pace, so it needs steps
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(60_000L), epoch(180_000L), 0))
        assertTrue("Zero steps across a jog is evidence of a vehicle", TrackingManager.hasDoubt())
        assertTrue(TrackingManager.walk.value.bridgedMeters > 0.0)

        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, epoch(60_000L), epoch(180_000L), 300)))

        val state = TrackingManager.walk.value
        assertFalse("The watch saw it walked", TrackingManager.hasDoubt())
        assertEquals(0.0, state.bridgedMeters, 0.0)
        assertEquals(0, state.phoneSteps)
        assertEquals(300, state.watchSteps)
    }

    /**
     * A doubtful start is set aside as a drive to the walk rather than bridged,
     * and still worth asking the watch about: its steps can say it was walked.
     */
    @Test
    fun `watch steps can give a walk back the start it was walked from`() {
        startWalking()
        go(seconds = 120, mps = 3.5)
        go(seconds = 60)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(120_000L), 0))
        assertTrue(TrackingManager.hasDoubt())
        assertTrue("Begun in a car, by the phone's account", TrackingManager.walk.value.startIndex > 0)

        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, epoch(0L), epoch(120_000L), 300)))

        val state = TrackingManager.walk.value
        assertFalse(TrackingManager.hasDoubt())
        assertEquals(0, state.startIndex)
        assertEquals(120 * 3.5 + 60 * 1.4, state.onFootMeters, 1.0)
    }

    @Test
    fun `a watch that resends what it sent before counts it once`() {
        startWalking()
        go(seconds = 180)
        val sent = listOf(
            StepInterval(StepSource.WATCH, epoch(0L), epoch(60_000L), 80),
            StepInterval(StepSource.WATCH, epoch(60_000L), epoch(120_000L), 85),
        )
        TrackingManager.onSteps(sent)
        TrackingManager.onSteps(sent + StepInterval(StepSource.WATCH, epoch(120_000L), epoch(180_000L), 90))

        val state = TrackingManager.walk.value
        assertEquals(80 + 85 + 90, state.watchSteps)
        assertEquals(3, state.steps.count { it.source == StepSource.WATCH })
    }

    @Test
    fun `a watch interval overlapping one already counted is dropped`() {
        startWalking()
        go(seconds = 120)
        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, epoch(0L), epoch(60_000L), 80)))
        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, epoch(30_000L), epoch(90_000L), 70)))

        assertEquals(80, TrackingManager.walk.value.watchSteps)
    }

    @Test
    fun `the watch's steps and the phone's are counted apart`() {
        startWalking()
        go(seconds = 60)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(60_000L), 78))
        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, epoch(0L), epoch(60_000L), 81)))

        val state = TrackingManager.walk.value
        assertEquals(78, state.phoneSteps)
        assertEquals("The same minute from another witness is not a repeat", 81, state.watchSteps)
    }

    @Test
    fun `nothing to ask the watch about a walk at walking pace`() {
        startWalking()
        go(seconds = 120)
        assertFalse(TrackingManager.hasDoubt())
    }

    @Test
    fun `nothing to ask the watch about a walk that is never judged`() {
        TrackingManager.startWalk(relaxedThresholds = true, activityType = ActivityType.WALK)
        go(seconds = 120, mps = 3.5)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(120_000L), 0))
        assertFalse(TrackingManager.hasDoubt())
    }

    @Test
    fun `nothing to ask the watch once the walk is over`() {
        startWalking()
        go(seconds = 120, mps = 3.5)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(120_000L), 0))
        TrackingManager.cancelWalk()
        assertFalse(TrackingManager.hasDoubt())
    }

    @Test
    fun `steps for a walk that is over are dropped`() {
        startWalking()
        go(seconds = 60)
        val start = epoch(0L)
        TrackingManager.cancelWalk()
        TrackingManager.onSteps(listOf(StepInterval(StepSource.WATCH, start, start + 60_000L, 80)))
        assertEquals(null, TrackingManager.walk.value.watchSteps)
    }

    // --- helpers -------------------------------------------------------------

    private fun startWalking() =
        TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)

    private var t = 0L
    private var metres = 0.0

    /** A fix every 3 s due north at [mps], for [seconds] — after one at the origin, if the walk has none. */
    private fun go(seconds: Int, mps: Double = 1.4) {
        if (TrackingManager.walk.value.path.isEmpty()) fix()
        repeat(seconds / 3) {
            t += 3_000L
            metres += 3 * mps
            fix()
        }
    }

    private fun fix() = TrackingManager.onLocation(
        point = LatLng(ATHENS.lat + metres / 111_195.0, ATHENS.lng),
        accuracyMeters = 5f,
        atElapsedMs = t,
    )

    /** The wall-clock time of [elapsedMs] into the walk, as the steps are timed. */
    private fun epoch(elapsedMs: Long): Long =
        TrackingManager.walk.value.track.first().atEpochMs!! + elapsedMs

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
