package io.app.enclose.tracking

import io.app.enclose.geo.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the thing feeding this manager reports that it cannot run.
 *
 * Before this, [LocationService] answered that question by stopping itself in
 * silence: the manager stayed `isTracking`, the panel went on saying "Walking",
 * and the path could never grow. The only way out was Stop-and-discard.
 */
class TrackingManagerRecordingFailureTest {

    @After
    fun clearWalk() {
        // TrackingManager is a singleton object; don't leak state across tests.
        TrackingManager.cancelWalk()
    }

    @Test
    fun `a walk with nothing recorded is dropped back to idle`() {
        TrackingManager.startWalk()

        TrackingManager.reportRecordingUnavailable(RecordingFailure.PERMISSION)

        val state = TrackingManager.walk.value
        assertFalse(state.isTracking)
        assertTrue(state.path.isEmpty())
    }

    /**
     * The other half of the no-data-loss rule: those points are ground someone
     * actually covered, so the walk stays up and Stop can still claim it. A
     * problem is reported, never paid for with a walk.
     */
    @Test
    fun `a walk that has recorded ground keeps running`() {
        TrackingManager.startWalk()
        TrackingManager.onLocation(LatLng(52.0, 13.0), accuracyMeters = 5f)
        TrackingManager.onLocation(LatLng(52.001, 13.0), accuracyMeters = 5f)
        val recorded = TrackingManager.walk.value.path

        TrackingManager.reportRecordingUnavailable(RecordingFailure.UNAVAILABLE)

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertEquals(recorded, state.path)
        assertEquals(RecordingFailure.UNAVAILABLE, state.recordingFailure)
    }

    /** A fix arriving is the only proof recording works, so it is what clears it. */
    @Test
    fun `the next fix clears the reported failure`() {
        TrackingManager.startWalk()
        TrackingManager.onLocation(LatLng(52.0, 13.0), accuracyMeters = 5f)
        TrackingManager.reportRecordingUnavailable(RecordingFailure.UNAVAILABLE)

        TrackingManager.onLocation(LatLng(52.001, 13.0), accuracyMeters = 5f)

        assertNull(TrackingManager.walk.value.recordingFailure)
    }

    @Test
    fun `reporting against no walk changes nothing`() {
        TrackingManager.cancelWalk()

        TrackingManager.reportRecordingUnavailable(RecordingFailure.PERMISSION)

        assertFalse(TrackingManager.walk.value.isTracking)
        assertNull(TrackingManager.walk.value.recordingFailure)
    }
}
