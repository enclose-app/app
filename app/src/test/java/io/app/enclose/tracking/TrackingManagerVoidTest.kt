package io.app.enclose.tracking

import io.app.enclose.data.VoidedWalk
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A void ends the walk without a claim — and, since most voids are honest walks
 * the classifier misread, it hands over what was walked instead of erasing it.
 */
class TrackingManagerVoidTest {

    @After
    fun clearWalk() {
        TrackingManager.cancelWalk()
        TrackingManager.clearPending()
    }

    @Test
    fun `a voided walk hands over its path before it is reset`() = runBlocking {
        val handed = mutableListOf<TrackingManager.VoidedRecording>()
        val reasons = mutableListOf<VoidReason>()
        val recordings = launch(start = CoroutineStart.UNDISPATCHED) {
            TrackingManager.voidedRecordings.collect { handed += it }
        }
        val voids = launch(start = CoroutineStart.UNDISPATCHED) {
            TrackingManager.voidEvents.collect { reasons += it }
        }

        TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)
        // A short honest walk...
        var t = 0L
        var m = 0.0
        repeat(10) {
            TrackingManager.onLocation(at(m), accuracyMeters = 5f, atElapsedMs = t)
            t += 3_000L
            m += 4.5
        }
        val walkedBeforeDriving = TrackingManager.walk.value.path.size
        // ...then 25 m/s for ten minutes: past every strike's grace window.
        repeat(200) {
            if (!TrackingManager.walk.value.isTracking) return@repeat
            TrackingManager.onLocation(at(m), accuracyMeters = 5f, atElapsedMs = t)
            t += 3_000L
            m += 75.0
        }
        yield()
        recordings.cancel()
        voids.cancel()

        assertFalse("The void still ends the walk", TrackingManager.walk.value.isTracking)
        assertEquals(1, reasons.size)
        assertEquals(1, handed.size)
        val recording = handed.single()
        assertEquals(reasons.single(), recording.reason)
        assertTrue(
            "The honest stretch is in the handed-over path",
            recording.path.size >= walkedBeforeDriving,
        )
        assertTrue(recording.distanceMeters > 0.0)
    }

    @Test
    fun `a walk voided before it recorded anything hands over nothing`() = runBlocking {
        val handed = mutableListOf<TrackingManager.VoidedRecording>()
        val recordings = launch(start = CoroutineStart.UNDISPATCHED) {
            TrackingManager.voidedRecordings.collect { handed += it }
        }
        TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)
        TrackingManager.cancelWalk()
        yield()
        recordings.cancel()
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `every void reason has a stored reason of the same name`() {
        // The stored record mirrors VoidReason by name (data can't import
        // tracking). A reason added here without one there would be saved as
        // the fallback — a walk recorded as "vehicle" that wasn't.
        VoidReason.entries.forEach { reason ->
            assertEquals(reason.name, VoidedWalk.Reason.of(reason.name).name)
        }
    }

    private fun at(meters: Double) = LatLng(ATHENS.lat + meters / 111_195.0, ATHENS.lng)

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
