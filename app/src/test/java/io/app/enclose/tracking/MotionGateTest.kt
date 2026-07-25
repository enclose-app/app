package io.app.enclose.tracking

import io.app.enclose.tracking.MotionGate.Companion.GRACE_MS
import io.app.enclose.tracking.MotionGate.Companion.SPEED_WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The gate decides whether a trip can become a claim, so its thresholds are
 * pinned here: walking and cycling must always pass, driving must never, and a
 * single bad fix must not cost someone their walk.
 */
class MotionGateTest {

    private val gate = MotionGate()

    /** Most tests declare BIKE, the loosest mode, so speed alone is under test. */
    private fun gateFor(type: ActivityType) = MotionGate().apply { reset(type) }

    /** Feed [count] fixes at [speed], 3 s apart, and return the last verdict. */
    private fun feed(
        speed: Double,
        count: Int = SPEED_WINDOW,
        motion: MotionSample? = null,
        startAtMs: Long = 0L,
        gate: MotionGate = this.gate,
    ): MotionGate.Verdict {
        var verdict: MotionGate.Verdict = MotionGate.Verdict.Allowed
        repeat(count) { i ->
            val now = startAtMs + i * FIX_INTERVAL_MS
            verdict = gate.evaluate(now, speed, motion?.copy(atElapsedMs = now))
        }
        return verdict
    }

    @Before
    fun declareBike() {
        // The suite's baseline is the loosest mode, so the speed rules themselves
        // are what's under test; per-mode tightening is covered separately below.
        gate.reset(ActivityType.BIKE)
    }

    private fun sample(activity: MotionActivity, confidence: Int, vehicle: Int = 0) =
        MotionSample(
            activity = activity,
            confidence = confidence,
            vehicleConfidence = vehicle,
        )

    @Test
    fun `walking pace is allowed`() {
        assertEquals(MotionGate.Verdict.Allowed, feed(WALKING_MPS))
    }

    @Test
    fun `running pace is allowed`() {
        assertEquals(MotionGate.Verdict.Allowed, feed(RUNNING_MPS))
    }

    @Test
    fun `ordinary cycling pace is allowed without any classification`() {
        assertEquals(MotionGate.Verdict.Allowed, feed(CYCLING_MPS))
    }

    @Test
    fun `no signals at all is allowed`() {
        assertEquals(MotionGate.Verdict.Allowed, gate.evaluate(0L, null, null))
    }

    @Test
    fun `driving speed is blocked on speed alone`() {
        val verdict = feed(DRIVING_MPS)
        assertTrue("expected blocked, was $verdict", verdict is MotionGate.Verdict.Blocked)
        assertEquals(BlockReason.TOO_FAST, (verdict as MotionGate.Verdict.Blocked).reason)
    }

    @Test
    fun `slow city driving is blocked by the activity classifier`() {
        // 7 m/s (25 km/h) is under the speed ceiling — only the classifier catches it.
        val verdict = feed(
            speed = 7.0,
            motion = sample(MotionActivity.VEHICLE, confidence = 85, vehicle = 85),
        )
        assertTrue(verdict is MotionGate.Verdict.Blocked)
        assertEquals(BlockReason.VEHICLE, (verdict as MotionGate.Verdict.Blocked).reason)
    }

    @Test
    fun `a car stopped at a light still blocks via vehicle confidence`() {
        // Most-probable activity is STILL, but in-vehicle confidence stays high.
        val verdict = feed(
            speed = 0.2,
            motion = sample(MotionActivity.STILL, confidence = 60, vehicle = 75),
        )
        assertTrue(verdict is MotionGate.Verdict.Blocked)
        assertEquals(BlockReason.VEHICLE, (verdict as MotionGate.Verdict.Blocked).reason)
    }

    @Test
    fun `a confident cycling reading excuses fast riding`() {
        val verdict = feed(
            speed = 12.0, // 43 km/h — above the speed ceiling
            motion = sample(MotionActivity.CYCLING, confidence = 80),
        )
        assertEquals(MotionGate.Verdict.Allowed, verdict)
    }

    @Test
    fun `no classification can excuse absurd speed`() {
        val verdict = feed(
            speed = 25.0, // 90 km/h
            motion = sample(MotionActivity.CYCLING, confidence = 95),
        )
        assertTrue(verdict is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `a stale walking reading cannot excuse a drive`() {
        val stale = MotionSample(
            activity = MotionActivity.WALKING,
            confidence = 95,
            atElapsedMs = 0L,
        )
        // Same reading, evaluated well past its shelf life.
        val now = MotionGate.ACTIVITY_MAX_AGE_MS + 10_000L
        var verdict: MotionGate.Verdict = MotionGate.Verdict.Allowed
        repeat(SPEED_WINDOW) { i ->
            verdict = gate.evaluate(now + i * FIX_INTERVAL_MS, DRIVING_MPS, stale)
        }
        assertTrue(verdict is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `a single GPS spike does not block a walk`() {
        repeat(SPEED_WINDOW) { gate.evaluate(it * FIX_INTERVAL_MS, WALKING_MPS, null) }
        // One wild fix among walking-speed samples stays under the average ceiling.
        val verdict = gate.evaluate(SPEED_WINDOW * FIX_INTERVAL_MS, 22.0, null)
        assertEquals(MotionGate.Verdict.Allowed, verdict)
    }

    @Test
    fun `sustained blocking voids the walk after the grace window`() {
        feed(DRIVING_MPS, count = SPEED_WINDOW)
        // Keep driving past the grace window.
        val past = GRACE_MS + SPEED_WINDOW * FIX_INTERVAL_MS
        val verdict = gate.evaluate(past, DRIVING_MPS, null)
        assertTrue("expected void, was $verdict", verdict is MotionGate.Verdict.Void)
        assertEquals(BlockReason.TOO_FAST, (verdict as MotionGate.Verdict.Void).reason)
    }

    @Test
    fun `blocking does not void before the grace window elapses`() {
        feed(DRIVING_MPS, count = SPEED_WINDOW)
        val justInside = GRACE_MS - 1_000L
        val verdict = gate.evaluate(justInside, DRIVING_MPS, null)
        assertTrue(verdict is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `slowing back down clears the block and restarts the grace window`() {
        feed(DRIVING_MPS, count = SPEED_WINDOW)
        // Walking speed drags the average back under the ceiling.
        repeat(SPEED_WINDOW) { i ->
            gate.evaluate(20_000L + i * FIX_INTERVAL_MS, WALKING_MPS, null)
        }
        assertEquals(
            MotionGate.Verdict.Allowed,
            gate.evaluate(40_000L, WALKING_MPS, null),
        )
        // A later drive gets its own full grace window rather than voiding at once.
        val verdict = feed(DRIVING_MPS, count = SPEED_WINDOW, startAtMs = 50_000L)
        assertTrue(verdict is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `declaring a walk rejects cycling speed`() {
        // The point of the selector: a walker is held to a walking pace, which
        // catches slow driving that a cycling ceiling would wave through.
        val verdict = feed(CYCLING_MPS, gate = gateFor(ActivityType.WALK))
        assertTrue(verdict is MotionGate.Verdict.Blocked)
        assertEquals(BlockReason.TOO_FAST, (verdict as MotionGate.Verdict.Blocked).reason)
    }

    @Test
    fun `declaring a walk still allows a brisk walk`() {
        assertEquals(
            MotionGate.Verdict.Allowed,
            feed(WALKING_MPS, gate = gateFor(ActivityType.WALK)),
        )
    }

    @Test
    fun `declaring a run allows running but not cycling speed`() {
        assertEquals(
            MotionGate.Verdict.Allowed,
            feed(RUNNING_MPS, gate = gateFor(ActivityType.RUN)),
        )
        assertTrue(feed(12.0, gate = gateFor(ActivityType.RUN)) is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `a declared walk that turns into a ride is upgraded, not voided`() {
        // Detected cycling raises the ceiling rather than punishing the user.
        val verdict = feed(
            speed = CYCLING_MPS,
            motion = sample(MotionActivity.CYCLING, confidence = 80),
            gate = gateFor(ActivityType.WALK),
        )
        assertEquals(MotionGate.Verdict.Allowed, verdict)
    }

    @Test
    fun `declaring a bike does not excuse driving`() {
        // The loosest declaration still can't beat the classifier or the ceiling.
        val byClassifier = feed(
            speed = 7.0,
            motion = sample(MotionActivity.VEHICLE, confidence = 90, vehicle = 90),
            gate = gateFor(ActivityType.BIKE),
        )
        assertTrue(byClassifier is MotionGate.Verdict.Blocked)
        assertTrue(feed(DRIVING_MPS, gate = gateFor(ActivityType.BIKE)) is MotionGate.Verdict.Blocked)
    }

    @Test
    fun `reset forgets the previous walk`() {
        feed(DRIVING_MPS, count = SPEED_WINDOW)
        gate.reset()
        assertEquals(MotionGate.Verdict.Allowed, gate.evaluate(0L, WALKING_MPS, null))
    }

    private companion object {
        const val FIX_INTERVAL_MS = 3_000L
        const val WALKING_MPS = 1.4 // ~5 km/h
        const val RUNNING_MPS = 3.3 // ~12 km/h
        const val CYCLING_MPS = 5.5 // ~20 km/h
        const val DRIVING_MPS = 16.0 // ~58 km/h
    }
}
