package io.app.enclose.tracking

import io.app.enclose.data.ProgressPoint
import io.app.enclose.data.ProgressSteps
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
    fun `each point goes down with its time and what it was judged on`() = runBlocking {
        val track = listOf(
            TrackPoint(atEpochMs = 1_000L),
            TrackPoint(atEpochMs = 4_000L, motion = MotionEvidence.VEHICLE),
            TrackPoint(atEpochMs = 90_000L, afterGap = true),
        )
        recorder.onState(tracking(path(3), track = track))

        // A restored walk is judged on exactly these, so none of it may be lost.
        assertEquals(
            listOf(
                ProgressPoint(path(3)[0], atEpochMs = 1_000L),
                ProgressPoint(path(3)[1], atEpochMs = 4_000L, motion = "VEHICLE"),
                ProgressPoint(path(3)[2], atEpochMs = 90_000L, afterGap = true),
            ),
            store.points,
        )
    }

    @Test
    fun `a point with no clock behind it is stored without one`() = runBlocking {
        // A state built without a track — a tap, a replayed GPX point.
        recorder.onState(tracking(path(2)))

        assertEquals(listOf(null, null), store.points.map { it.atEpochMs })
        assertEquals(listOf(false, false), store.points.map { it.afterGap })
    }

    @Test
    fun `step reports are appended once each`() = runBlocking {
        val first = StepInterval(StepSource.PHONE, 0L, 10_000L, 14)
        val second = StepInterval(StepSource.WATCH, 0L, 12_000L, 16)
        recorder.onState(tracking(path(1), steps = listOf(first)))
        recorder.onState(tracking(path(2), steps = listOf(first)))
        recorder.onState(tracking(path(2), steps = listOf(first, second)))

        assertEquals(
            listOf(ProgressSteps("PHONE", 0L, 10_000L, 14), ProgressSteps("WATCH", 0L, 12_000L, 16)),
            store.steps,
        )
        assertEquals("One write per new report", 2, store.stepAppendCalls)
    }

    @Test
    fun `an adopted session does not rewrite the restored steps`() = runBlocking {
        val restored = listOf(
            StepInterval(StepSource.PHONE, 0L, 10_000L, 14),
            StepInterval(StepSource.PHONE, 10_000L, 20_000L, 15),
        )
        recorder.adopt(pointCount = 2, stepCount = 2)
        recorder.onState(tracking(path(2), steps = restored))

        assertEquals(0, store.stepAppendCalls)

        recorder.onState(tracking(path(2), steps = restored + StepInterval(StepSource.PHONE, 20_000L, 30_000L, 0)))
        assertEquals(
            "A report of no steps is evidence, and is kept",
            listOf(ProgressSteps("PHONE", 20_000L, 30_000L, 0)),
            store.steps,
        )
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
        track: List<TrackPoint> = emptyList(),
        steps: List<StepInterval> = emptyList(),
    ) = TrackingManager.WalkState(
        isTracking = true,
        path = path,
        track = track,
        steps = steps,
        start = path.firstOrNull(),
        current = path.lastOrNull(),
        startedAtMs = if (path.isEmpty()) null else 1_000L,
        elevationGainMeters = climb,
        movingMs = movingMs,
    )

    private fun idle() = TrackingManager.WalkState(isTracking = false)

    private class FakeStore : WalkProgressStore {
        val points = mutableListOf<ProgressPoint>()
        val appended get() = points.map { it.position }
        val appendSizes = mutableListOf<Int>()
        var appendCalls = 0
        val steps = mutableListOf<ProgressSteps>()
        var stepAppendCalls = 0
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

        override suspend fun append(points: List<ProgressPoint>) {
            appendCalls++
            appendSizes += points.size
            this.points += points
        }

        override suspend fun appendSteps(steps: List<ProgressSteps>) {
            stepAppendCalls++
            this.steps += steps
        }

        override suspend fun clear() {
            clears++
        }
    }
}
