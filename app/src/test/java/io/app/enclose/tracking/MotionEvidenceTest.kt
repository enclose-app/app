package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the activity classifier is allowed to say about a fix. Only a confident,
 * fresh reading that could change a verdict is kept — anything weaker is no
 * evidence, which in this app is never the same as evidence against.
 */
class MotionEvidenceTest {

    @Test
    fun `a confident vehicle reading is kept, whatever the activity says`() {
        // A car stopped at a light classifies as STILL with a high in-vehicle score.
        val sample = sample(MotionActivity.STILL, confidence = 90, vehicleConfidence = 75)
        assertEquals(MotionEvidence.VEHICLE, MotionEvidence.of(sample, atElapsedMs = NOW))
    }

    @Test
    fun `a vehicle reading under the bar counts against nobody`() {
        val sample = sample(MotionActivity.VEHICLE, confidence = 90, vehicleConfidence = 69)
        assertNull(MotionEvidence.of(sample, atElapsedMs = NOW))
    }

    @Test
    fun `a confident run or ride is kept`() {
        assertEquals(MotionEvidence.RUNNING, MotionEvidence.of(sample(MotionActivity.RUNNING, 60), NOW))
        assertEquals(MotionEvidence.CYCLING, MotionEvidence.of(sample(MotionActivity.CYCLING, 80), NOW))
    }

    @Test
    fun `an unsure run raises nothing`() {
        assertNull(MotionEvidence.of(sample(MotionActivity.RUNNING, confidence = 59), NOW))
    }

    @Test
    fun `walking and standing say nothing a walk needs`() {
        assertNull(MotionEvidence.of(sample(MotionActivity.WALKING, 100), NOW))
        assertNull(MotionEvidence.of(sample(MotionActivity.STILL, 100), NOW))
        assertNull(MotionEvidence.of(sample(MotionActivity.UNKNOWN, 100), NOW))
    }

    @Test
    fun `a stale reading is worse than none`() {
        // A run classified long ago must not vouch for the fix taken now.
        val old = sample(MotionActivity.CYCLING, 90, atElapsedMs = NOW - MotionEvidence.ACTIVITY_MAX_AGE_MS - 1)
        assertNull(MotionEvidence.of(old, NOW))
    }

    @Test
    fun `no reading, or no clock to age it by, is no evidence`() {
        assertNull(MotionEvidence.of(null, NOW))
        assertNull(MotionEvidence.of(sample(MotionActivity.CYCLING, 90), atElapsedMs = null))
    }

    // --- helpers -------------------------------------------------------------

    private fun sample(
        activity: MotionActivity,
        confidence: Int,
        vehicleConfidence: Int = 0,
        atElapsedMs: Long = NOW - 1_000L,
    ) = MotionSample(activity, confidence, vehicleConfidence, atElapsedMs)

    private companion object {
        const val NOW = 600_000L
    }
}
