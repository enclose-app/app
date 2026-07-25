package io.app.enclose.tracking

import io.app.enclose.data.WalkProgressStore
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder is what stands between a low-memory kill and an hour of walking,
 * so what it writes — and, just as importantly, what it doesn't write twice —
 * is pinned here.
 */
class WalkProgressRecorderTest {

    private val store = FakeStore()
    private val recorder = WalkProgressRecorder(store)

    @Test
    fun `points are appended as the walk grows, never rewritten`() = runBlocking {
        recorder.onState(tracking(path(1)))
        recorder.onState(tracking(path(2)))
        recorder.onState(tracking(path(3)))

        // One append per new point: the path is written once, not re-sent whole
        // on every fix, so a long walk doesn't get more expensive per step.
        assertEquals(listOf(1, 1, 1), store.appendSizes)
        assertEquals(3, store.appended.size)
        assertEquals(path(3), store.appended)
    }

    @Test
    fun `a session opens only once for a walk`() = runBlocking {
        recorder.onState(tracking(path(1)))
        recorder.onState(tracking(path(2)))

        assertEquals(1, store.begins)
    }

    @Test
    fun `nothing is written before the first fix lands`() = runBlocking {
        recorder.onState(tracking(emptyList()))

        assertEquals(0, store.begins)
        assertTrue(store.appended.isEmpty())
    }

    @Test
    fun `an adopted session does not rewrite the restored path`() = runBlocking {
        // What LocationService does after restoring three points from disk.
        recorder.adopt(pointCount = 3)
        recorder.onState(tracking(path(3)))

        assertEquals("The restored path is already stored", 0, store.appendCalls)
        assertEquals("Resuming is not a new session", 0, store.begins)

        // Only genuinely new ground gets written.
        recorder.onState(tracking(path(4)))
        assertEquals(1, store.appended.size)
    }

    @Test
    fun `climb is written only when it actually moves`() = runBlocking {
        recorder.onState(tracking(path(1), climb = 0.0))
        recorder.onState(tracking(path(2), climb = 0.0))
        recorder.onState(tracking(path(3), climb = 12.0))
        recorder.onState(tracking(path(4), climb = 12.0))

        // The noise gate leaves most fixes unchanged; those must not each cost
        // a write on the recording path.
        assertEquals(1, store.elevationWrites)
        assertEquals(12.0, store.lastGain, 0.001)
    }

    @Test
    fun `an adopted session does not rewrite the restored climb`() = runBlocking {
        recorder.adopt(pointCount = 3, elevationGainMeters = 40.0, movingMs = 90_000L)
        recorder.onState(tracking(path(3), climb = 40.0, movingMs = 90_000L))

        assertEquals("Already on disk", 0, store.elevationWrites)

        recorder.onState(tracking(path(4), climb = 55.0, movingMs = 120_000L))
        assertEquals(1, store.elevationWrites)
        assertEquals(55.0, store.lastGain, 0.001)
        assertEquals(120_000L, store.lastMovingMs)
    }

    @Test
    fun `ending a walk clears the session`() = runBlocking {
        recorder.onState(tracking(path(2)))
        recorder.onState(idle())

        assertEquals(1, store.clears)
    }

    @Test
    fun `an already ended walk is not cleared over and over`() = runBlocking {
        recorder.onState(tracking(path(2)))
        recorder.onState(idle())
        recorder.onState(idle())
        recorder.onState(idle())

        assertEquals("Idle states shouldn't keep hitting storage", 1, store.clears)
    }

    @Test
    fun `a new walk after one ends starts a fresh session`() = runBlocking {
        recorder.onState(tracking(path(2)))
        recorder.onState(idle())
        recorder.onState(tracking(path(1)))

        assertEquals(2, store.begins)
        // The second walk's single point, not a continuation of the first.
        assertEquals(3, store.appended.size)
    }

    // --- helpers -------------------------------------------------------------

    private fun path(n: Int): List<LatLng> =
        (1..n).map { LatLng(37.9838 + it * 0.0001, 23.7275) }

    private fun tracking(
        path: List<LatLng>,
        climb: Double = 0.0,
        movingMs: Long = 0L,
    ) = TrackingManager.WalkState(
        isTracking = true,
        path = path,
        start = path.firstOrNull(),
        current = path.lastOrNull(),
        startedAtMs = if (path.isEmpty()) null else 1_000L,
        elevationGainMeters = climb,
        movingMs = movingMs,
    )

    private fun idle() = TrackingManager.WalkState(isTracking = false)

    private class FakeStore : WalkProgressStore {
        val appended = mutableListOf<LatLng>()
        val appendSizes = mutableListOf<Int>()
        var appendCalls = 0
        var begins = 0
        var clears = 0
        var elevationWrites = 0
        var lastGain = 0.0
        var lastMovingMs = 0L

        override suspend fun begin(startedAtEpochMs: Long, activityTypeName: String) {
            begins++
        }

        override suspend fun setTotals(elevationGainMeters: Double, movingMs: Long) {
            elevationWrites++
            lastGain = elevationGainMeters
            lastMovingMs = movingMs
        }

        override suspend fun append(points: List<LatLng>) {
            appendCalls++
            appendSizes += points.size
            appended += points
        }

        override suspend fun clear() {
            clears++
        }
    }
}
