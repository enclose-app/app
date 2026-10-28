package io.app.enclose.tracking

import io.app.enclose.data.VoidedWalk
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * How a walk ends. Only the walker ends one — Stop, Save, or Discard behind its
 * confirmation — and only Discard loses anything. These used to pin the
 * opposite: that the app voided a walk on its own after three warnings.
 */
class TrackingManagerStopTest {

    private var t = 0L
    private var here = ATHENS

    @After
    fun clearWalk() {
        TrackingManager.cancelWalk()
        TrackingManager.clearPending()
    }

    @Test
    fun `stop away from the start with no loop keeps the walk`() = runBlocking {
        val kept = collectUnclaimed {
            start()
            walkTo(north(ATHENS, 300.0))
            TrackingManager.finishWalk()
        }

        assertFalse(TrackingManager.walk.value.isTracking)
        assertNull("Nothing closed, so nothing to claim", TrackingManager.pendingClaim.value)
        val walk = kept.single()
        assertEquals(UnclaimedReason.NOT_CLOSED, walk.reason)
        assertEquals("Every fix is handed over", 1 + 72, walk.path.size)
        assertEquals(300.0, walk.distanceMeters, 1.0)
        assertEquals(300.0, walk.onFootMeters, 1.0)
    }

    @Test
    fun `stop away from the start claims the loop closed earlier`() {
        start()
        walkSquare(sideMeters = 150.0)
        val loopPoints = TrackingManager.walk.value.path.size
        // Forgot to press Stop, and walked on.
        walkTo(east(ATHENS, -300.0))
        assertFalse(TrackingManager.walk.value.readyToClose)

        TrackingManager.finishWalk()

        val pending = TrackingManager.pendingClaim.value
        assertNotNull("The loop closed before the walk went on", pending)
        assertEquals("Closed where the walk began", ATHENS, pending!!.ring.first())
        assertEquals(600.0, pending.perimeterMeters, 15.0)
        assertTrue("The walk on is not part of the loop", pending.ring.size <= loopPoints)
        assertEquals("...but it is kept with the walk", loopPoints + 72, pending.recordedTrack?.size)
    }

    @Test
    fun `the earlier loop is found, with when it closed`() {
        start()
        walkTo(north(ATHENS, 150.0))
        assertNull("No loop yet", TrackingManager.earlierLoop())

        walkTo(east(north(ATHENS, 150.0), 150.0))
        walkTo(east(ATHENS, 150.0))
        walkTo(ATHENS)
        val closedAt = TrackingManager.walk.value.track.last().atEpochMs
        walkTo(east(ATHENS, -200.0))

        assertEquals(TrackingManager.EarlierLoop(closedAt), TrackingManager.earlierLoop())
    }

    /** The plan's "Looks like a vehicle. Your loop closed at 14:32." */
    @Test
    fun `a drive after the loop closed says when the loop closed`() {
        start()
        walkSquare(sideMeters = 150.0)
        val closedAt = TrackingManager.walk.value.track.last().atEpochMs
        assertNull("Nothing in doubt yet", TrackingManager.walk.value.earlierLoopClosedAtMs)

        walkTo(east(ATHENS, -1_000.0), mps = 15.0)

        val riding = TrackingManager.walk.value
        assertTrue(riding.doubtful)
        assertEquals(closedAt, riding.earlierLoopClosedAtMs)

        walkTo(east(ATHENS, -1_100.0))
        assertNull("Said only while it is in doubt", TrackingManager.walk.value.earlierLoopClosedAtMs)
    }

    @Test
    fun `save keeps a walk without a claim, even one standing at its start`() = runBlocking {
        val kept = collectUnclaimed {
            start()
            walkSquare(sideMeters = 150.0)
            assertTrue(TrackingManager.walk.value.readyToClose)
            TrackingManager.saveWalk()
        }

        assertNull(TrackingManager.pendingClaim.value)
        assertEquals(UnclaimedReason.NOT_CLOSED, kept.single().reason)
        assertFalse(TrackingManager.walk.value.isTracking)
    }

    @Test
    fun `a whole walk in a car is kept, and claims nothing`() = runBlocking {
        val kept = collectUnclaimed {
            start()
            // A square loop driven at 15 m/s.
            walkSquare(sideMeters = 1_000.0, mps = 15.0)
            TrackingManager.finishWalk()
        }

        assertNull("A drive must not be able to enclose territory", TrackingManager.pendingClaim.value)
        val walk = kept.single()
        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, walk.reason)
        assertEquals("Its route is kept all the same", 4_000.0, walk.distanceMeters, 50.0)
    }

    /** The plan's tram ride: a short one in a long loop is bridged and the loop still claimed. */
    @Test
    fun `a short ride in a long loop is bridged and claimed`() {
        start()
        walkTramLoop()

        TrackingManager.finishWalk()

        val pending = TrackingManager.pendingClaim.value
        assertNotNull("A short ride must not cost the loop", pending)
        assertEquals(1, pending!!.bridges.size)
        assertTrue("The ride is bridged: ${pending.bridgedMeters}", pending.bridgedMeters in 400.0..700.0)
        assertTrue("...within the limit: ${pending.bridgedShare}", pending.bridgedShare <= LoopVerdict.MAX_BRIDGED_SHARE)
        assertEquals(
            "What the dialog shows adds up to the boundary",
            pending.perimeterMeters,
            pending.onFootMeters + pending.bridgedMeters,
            5.0,
        )
    }

    @Test
    fun `a walk restored after a process kill gets the verdict it would have got`() {
        start()
        walkTramLoop()
        val recorded = TrackingManager.walk.value
        TrackingManager.finishWalk()
        val uninterrupted = TrackingManager.pendingClaim.value
        assertNotNull(uninterrupted)
        TrackingManager.clearPending()

        // What LocationService hands back from disk after the kill.
        TrackingManager.restore(
            recorded.path,
            startedAtMs = recorded.startedAtMs!!,
            recorded.activityType,
            track = recorded.track,
            steps = recorded.steps,
        )
        TrackingManager.finishWalk()

        val restored = TrackingManager.pendingClaim.value
        assertNotNull("The restored walk still closes", restored)
        assertEquals(uninterrupted!!.ring, restored!!.ring)
        assertEquals(uninterrupted.bridges, restored.bridges)
        assertEquals(uninterrupted.onFootMeters, restored.onFootMeters, 0.001)
    }

    @Test
    fun `a loop mostly ridden is kept without a claim`() = runBlocking {
        val kept = collectUnclaimed {
            start()
            walkTo(north(ATHENS, 500.0))
            walkTo(east(north(ATHENS, 500.0), 500.0), mps = 10.0)
            walkTo(east(ATHENS, 500.0), mps = 10.0)
            walkTo(ATHENS)
            TrackingManager.finishWalk()
        }

        assertNull(TrackingManager.pendingClaim.value)
        val walk = kept.single()
        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, walk.reason)
        assertTrue(walk.bridgedShare > LoopVerdict.MAX_BRIDGED_SHARE)
        assertTrue(walk.bridgedMeters > 0.0)
    }

    @Test
    fun `discard is the one way a walk is lost`() = runBlocking {
        val kept = collectUnclaimed {
            start()
            walkTo(north(ATHENS, 100.0))
            TrackingManager.cancelWalk()
        }

        assertTrue(kept.isEmpty())
        assertFalse(TrackingManager.walk.value.isTracking)
    }

    @Test
    fun `a walk that recorded nothing hands over nothing`() = runBlocking {
        val kept = collectUnclaimed {
            TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)
            TrackingManager.finishWalk()
        }

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `every unclaimed reason has a stored reason of the same name`() {
        // The stored record mirrors UnclaimedReason by name (data can't import
        // tracking). A reason added here without one there would be saved as
        // the fallback — a walk recorded as "vehicle" that wasn't.
        UnclaimedReason.entries.forEach { reason ->
            assertEquals(reason.name, VoidedWalk.Reason.of(reason.name).name)
        }
        // And the walks the old anti-cheat ended still say why.
        listOf("VEHICLE", "TOO_FAST", "UNVERIFIED_GAP").forEach { old ->
            assertEquals(old, VoidedWalk.Reason.of(old).name)
        }
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun collectUnclaimed(block: () -> Unit): List<TrackingManager.UnclaimedRecording> =
        kotlinx.coroutines.coroutineScope {
            val kept = mutableListOf<TrackingManager.UnclaimedRecording>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                TrackingManager.unclaimedWalks.collect { kept += it }
            }
            block()
            yield()
            collector.cancel()
            kept
        }

    private fun start() {
        TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)
        t = 0L
        here = ATHENS
        fix(here)
    }

    /** A 4 km square with 450 m of it at tram speed, ending on the start. */
    private fun walkTramLoop() {
        val top = north(ATHENS, 1_000.0)
        walkTo(top)
        walkTo(east(top, 300.0))
        walkTo(east(top, 750.0), mps = 10.0)
        walkTo(east(top, 1_000.0))
        walkTo(east(ATHENS, 1_000.0))
        walkTo(ATHENS)
    }

    /** North, east, south and west again, ending on the start. */
    private fun walkSquare(sideMeters: Double, mps: Double = 1.4) {
        val corner = north(ATHENS, sideMeters)
        walkTo(corner, mps)
        walkTo(east(corner, sideMeters), mps)
        walkTo(east(ATHENS, sideMeters), mps)
        walkTo(ATHENS, mps)
    }

    /** A fix every three seconds along a straight line to [target], at [mps]. */
    private fun walkTo(target: LatLng, mps: Double = 1.4) {
        val from = here
        val count = ceil(Geo.distanceMeters(from, target) / (mps * 3.0)).toInt().coerceAtLeast(1)
        for (k in 1..count) {
            val f = k.toDouble() / count
            here = LatLng(from.lat + (target.lat - from.lat) * f, from.lng + (target.lng - from.lng) * f)
            t += 3_000L
            fix(here)
        }
    }

    private fun fix(point: LatLng) =
        TrackingManager.onLocation(point, accuracyMeters = 5f, atElapsedMs = t)

    private fun north(from: LatLng, meters: Double) = LatLng(from.lat + meters / 111_195.0, from.lng)

    private fun east(from: LatLng, meters: Double) =
        LatLng(from.lat, from.lng + meters / (111_195.0 * 0.788)) // cos(37.98°)

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
