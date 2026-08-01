package io.app.enclose.ui

import io.app.enclose.tracking.TrackingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The walk that starts, says "Walking", and never grows a path. Everything here
 * is about telling that apart from a GPS warm-up, which is what it was
 * indistinguishable from for as long as the user was willing to keep walking.
 */
class FixWatchTest {

    private fun warn(
        isTracking: Boolean = true,
        recordedPoints: Int = 0,
        accuracyMeters: Float? = null,
        waitingMs: Long = FixWatch.WARN_AFTER_MS,
    ) = FixWatch.warning(isTracking, recordedPoints, accuracyMeters, waitingMs)

    @Test
    fun `an idle map is never warned about`() {
        assertNull(warn(isTracking = false, waitingMs = Long.MAX_VALUE))
    }

    @Test
    fun `a cold start is left alone`() {
        assertNull(warn(waitingMs = FixWatch.WARN_AFTER_MS - 1))
    }

    @Test
    fun `no fix at all after the window is reported`() {
        assertEquals(FixWarning.NO_FIX, warn(accuracyMeters = null))
    }

    /**
     * The approximate-location case: fixes arrive on time, every one is discarded
     * by [TrackingManager.MAX_ACCURACY_METERS], and nothing on screen said so.
     */
    @Test
    fun `fixes too vague to keep are reported as such`() {
        assertEquals(
            FixWarning.TOO_VAGUE,
            warn(accuracyMeters = TrackingManager.MAX_ACCURACY_METERS + 1f),
        )
    }

    @Test
    fun `a fix exactly at the limit is kept, so nothing is said`() {
        assertNull(warn(accuracyMeters = TrackingManager.MAX_ACCURACY_METERS))
    }

    /**
     * One point is proof the whole chain works. Losing the signal later is the
     * signal-gap machinery's problem, and it is deliberately tolerant about it —
     * this must not second-guess that.
     */
    @Test
    fun `a recorded point ends the warning for good`() {
        assertNull(
            warn(
                recordedPoints = 1,
                accuracyMeters = 5_000f,
                waitingMs = Long.MAX_VALUE,
            ),
        )
    }
}
