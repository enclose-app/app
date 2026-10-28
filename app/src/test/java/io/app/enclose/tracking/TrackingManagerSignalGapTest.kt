package io.app.enclose.tracking

import io.app.enclose.geo.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a walk when the fixes stop arriving.
 *
 * Backgrounded and dozing devices stop delivering location, then hand over the
 * missed stretch in a burst on wake. Every one of these cases used to end with
 * the walk being thrown away — an hour on foot lost to the device going to
 * sleep, which is the one outcome this app can never make good, since a walked
 * territory cannot be re-created from the couch.
 *
 * The rule these pin: silence is not evidence of speed. What the recording never
 * observed is reported, not punished. The anti-cheat cases at the bottom pin the
 * other side of it — ground covered too fast for anything to explain is still
 * set aside, gap or no gap — and that nothing, on either side, ends the walk.
 */
class TrackingManagerSignalGapTest {

    @After
    fun clearWalk() {
        // TrackingManager is a singleton object; don't leak state across tests.
        TrackingManager.cancelWalk()
        TrackingManager.clearPending()
    }

    @Test
    fun `a long silence does not read as speed`() {
        startWalking()
        // Two honest walking fixes, then nothing for four minutes — the device
        // dozed — and the walker reappears 300 m up the road.
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(10.0), atElapsedMs = 3_000L)

        fix(at(310.0), atElapsedMs = 243_000L)

        val state = TrackingManager.walk.value
        assertTrue("The walk must survive the gap", state.isTracking)
        assertFalse("Silence is not a speeding offence", state.doubtful)
        assertEquals("...and nothing is bridged over it", 0.0, state.bridgedMeters, 0.0)
        assertEquals("The fix after the gap belongs on the path", 3, state.path.size)
    }

    @Test
    fun `a burst of batched fixes does not read as speed`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        // The whole dozing stretch arrives at once. Timed by delivery these are
        // milliseconds apart and imply hundreds of metres per second; timed by
        // their own clock they are an ordinary walk.
        for (i in 1..10) {
            fix(at(i * 30.0), atElapsedMs = i * 25_000L)
        }

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertFalse(state.doubtful)
        assertEquals(11, state.path.size)
    }

    @Test
    fun `a frozen fix snapping back does not read as speed`() {
        // The shape an emulator (and a real device indoors) actually produces:
        // the provider never goes quiet, it keeps reporting the last position it
        // was sure of at the normal interval, then snaps to the true one. The
        // silence rule cannot see this — the previous fix is 3 s old — so the
        // 307 m snap read as 100 m/s and discarded the walk on a device.
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(20.0), atElapsedMs = 6_000L)

        // Frozen: same position, still arriving every 3 s.
        var t = 6_000L
        repeat(20) {
            t += 3_000L
            fix(at(20.0), atElapsedMs = t)
        }
        // ...then it catches up.
        t += 3_000L
        fix(at(327.0), atElapsedMs = t)
        // ...and carries on walking normally from there.
        repeat(5) {
            t += 3_000L
            fix(at(327.0 + it * 4.5), atElapsedMs = t)
        }

        val state = TrackingManager.walk.value
        assertTrue("The walk must survive the snap", state.isTracking)
        // Judged from the last point the path kept, a minute earlier: 307 m in
        // 63 s is a brisk walk, not a jump.
        assertFalse(state.doubtful)
        assertTrue("The snap skipped ground nobody recorded; say so", state.hadSignalGap)
    }

    @Test
    fun `the walk remembers that it lost the signal`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        assertFalse(TrackingManager.walk.value.hadSignalGap)

        fix(at(300.0), atElapsedMs = 243_000L)

        assertTrue(
            "The route now bridges ground nobody recorded; say so",
            TrackingManager.walk.value.hadSignalGap,
        )
    }

    @Test
    fun `a gap flagged mid-walk survives to the claim`() {
        startWalking()
        // Out and back, so the loop is long enough and closes on its start.
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(150.0), atElapsedMs = 100_000L) // the gap
        fix(east(at(150.0), 60.0), atElapsedMs = 140_000L)
        fix(east(at(0.0), 60.0), atElapsedMs = 240_000L)
        fix(at(0.0), atElapsedMs = 300_000L)

        TrackingManager.finishWalk()

        val pending = TrackingManager.pendingClaim.value
        assertTrue("The loop should have closed", pending != null)
        assertTrue("The claim must carry the gap", pending!!.hadSignalGap)
    }

    @Test
    fun `a wildly inaccurate fix shapes nothing`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(10.0), atElapsedMs = 3_000L)

        // Reacquiring after signal loss: 800 m out, and the provider says so.
        // Fed to the speed window this alone used to be enough to void a walk.
        fix(at(800.0), atElapsedMs = 6_000L, accuracyMeters = 400f)

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertFalse("A fix that vague judges nothing", state.doubtful)
        assertEquals("...and shapes nothing", 2, state.path.size)
    }

    @Test
    fun `an inaccurate fix cannot anchor the start of a walk`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L, accuracyMeters = 400f)

        assertTrue(
            "A start anchored on a vague fix misplaces the whole loop",
            TrackingManager.walk.value.path.isEmpty(),
        )
    }

    // --- a single wild fix: Wi-Fi or cell positioning standing in for GPS ------

    @Test
    fun `a lone wild fix never reaches the path`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(5.0), atElapsedMs = 3_000L)
        // Claims ±25 m, lands 400 m out: the accuracy is the thing that's wrong.
        fix(east(at(5.0), 400.0), atElapsedMs = 6_000L, accuracyMeters = 25f)
        fix(at(10.0), atElapsedMs = 9_000L)
        fix(at(15.0), atElapsedMs = 12_000L)

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertEquals(listOf(at(0.0), at(5.0), at(10.0), at(15.0)), state.path)
        assertFalse("A dropped spike left no hole in the recording", state.hadSignalGap)
    }

    /**
     * What the emulator showed: the provider hands the same bad fix over more
     * than once, and a hold that trusted one repeat let the spike confirm itself.
     */
    @Test
    fun `a spike delivered twice still never reaches the path`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(5.0), atElapsedMs = 3_000L)
        fix(east(at(5.0), 400.0), atElapsedMs = 6_000L)
        fix(east(at(5.0), 400.0), atElapsedMs = 7_000L)
        fix(east(at(5.0), 400.0), atElapsedMs = 8_000L)
        fix(at(10.0), atElapsedMs = 9_000L)

        val state = TrackingManager.walk.value
        assertEquals(listOf(at(0.0), at(5.0), at(10.0)), state.path)
        assertFalse(state.hadSignalGap)
    }

    @Test
    fun `a jump that keeps agreeing with itself is kept`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(5.0), atElapsedMs = 3_000L)
        fix(at(300.0), atElapsedMs = 6_000L)
        fix(at(305.0), atElapsedMs = 9_000L)
        assertEquals("Held while it proves itself", 2, TrackingManager.walk.value.path.size)

        fix(at(310.0), atElapsedMs = 12_000L)
        fix(at(315.0), atElapsedMs = 15_000L)
        fix(at(320.0), atElapsedMs = 18_000L)

        val state = TrackingManager.walk.value
        assertEquals(
            listOf(at(0.0), at(5.0), at(300.0), at(305.0), at(310.0), at(315.0), at(320.0)),
            state.path,
        )
        assertTrue("The jump crossed ground nobody observed; say so", state.hadSignalGap)
    }

    @Test
    fun `a spike that outlasts the hold is cut from the claim`() {
        startWalking()
        // A bad fix repeated for long enough that the live hold accepts it; the
        // cleanup at Stop is what takes it off the claim.
        var t = 0L
        val square = listOf(at(0.0), at(100.0), east(at(100.0), 100.0), east(at(0.0), 100.0))
        for (corner in square) {
            fix(corner, atElapsedMs = t)
            t += 100_000L
        }
        t -= 50_000L
        fix(east(at(50.0), 100.0), atElapsedMs = t)
        val spike = east(at(50.0), 400.0)
        repeat(5) { fix(spike, atElapsedMs = t + 3_000L * (it + 1)) }
        t += 15_000L
        // Back on the path, walking on long enough for that to be accepted too.
        repeat(5) { fix(east(at(46.0 - it * 4.0), 100.0), atElapsedMs = t + 3_000L * (it + 1)) }
        fix(east(at(0.0), 100.0), atElapsedMs = t + 60_000L)
        fix(at(0.0), atElapsedMs = t + 140_000L)

        TrackingManager.finishWalk()

        val pending = TrackingManager.pendingClaim.value
        assertTrue("The loop should have closed", pending != null)
        assertFalse(pending!!.ring.contains(spike))
    }

    // --- the anti-cheat side, which must not have been loosened ---------------

    @Test
    fun `sustained driving is recorded and set aside, never ended`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        // 25 m/s ≈ 90 km/h for seven and a half minutes — long past every point
        // at which the old gate ended walks.
        var metres = 0.0
        var t = 0L
        repeat(150) {
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }

        val state = TrackingManager.walk.value
        assertTrue("Only the walker ends a walk", state.isTracking)
        assertEquals("Every fix is recorded", 151, state.path.size)
        assertTrue("...and none of the drive counts", state.doubtful)
        assertEquals(DoubtReason.TOO_FAST, state.doubtReason)
        assertTrue("A drive must not be able to enclose territory", state.onFootMeters < 100.0)
    }

    @Test
    fun `a drive in the middle of a walk is bridged, and the walk carries on`() {
        startWalking()
        var t = 0L
        var metres = 0.0
        // Ten minutes on foot...
        repeat(200) {
            fix(at(metres), atElapsedMs = t)
            metres += 4.2
            t += 3_000L
        }
        // ...two minutes at 15 m/s...
        repeat(40) {
            fix(at(metres), atElapsedMs = t)
            metres += 45.0
            t += 3_000L
        }
        // ...and walking again.
        repeat(40) {
            fix(at(metres), atElapsedMs = t)
            metres += 4.2
            t += 3_000L
        }

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertFalse("Walking again is walking", state.doubtful)
        assertEquals("One run of doubt, one bridge", 1, state.doubtfulRuns.size)
        assertTrue("The drive is bridged", state.bridgedMeters > 1_500.0)
        assertTrue("The walking either side still counts", state.onFootMeters > 900.0)
    }

    /**
     * The hole the old rules left open: switch location off, drive, switch it
     * back on. The silence used to be forgiven whole. It is now judged whole —
     * five kilometres in ten minutes is no walk, so the line across it is
     * bridged and counts against the loop.
     */
    @Test
    fun `location off, a drive, location on is bridged, not forgiven`() {
        startWalking()
        var t = 0L
        var metres = 0.0
        repeat(40) {
            fix(at(metres), atElapsedMs = t)
            metres += 4.2
            t += 3_000L
        }
        // Silence, and five kilometres further on.
        t += 600_000L
        metres += 5_000.0
        repeat(20) {
            fix(at(metres), atElapsedMs = t)
            metres += 4.2
            t += 3_000L
        }

        val state = TrackingManager.walk.value
        assertTrue("Still only the walker ends a walk", state.isTracking)
        assertTrue("The gap is reported", state.hadSignalGap)
        assertTrue("...and bridged", state.bridgedMeters > 4_900.0)
    }

    /** A dozing phone in a runner's pocket: the gap is fast for a walk, and the steps say it was run. */
    @Test
    fun `steps that cover a fast gap count it as walked`() {
        val gapMeters = 2_000.0
        val gapMs = 600_000L // 3.3 m/s: past walking pace, so it needs evidence
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(4.2), atElapsedMs = 3_000L)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(3_000L + gapMs), 1_800))
        fix(at(4.2 + gapMeters), atElapsedMs = 3_000L + gapMs)

        val state = TrackingManager.walk.value
        assertFalse(state.doubtful)
        assertEquals(0.0, state.bridgedMeters, 0.0)
        assertEquals(1_800, state.phoneSteps)
    }

    /** The same gap with the counter running and counting nothing: that is evidence, and it says vehicle. */
    @Test
    fun `a counter that counted nothing across a fast gap sets it aside`() {
        val gapMs = 600_000L
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(4.2), atElapsedMs = 3_000L)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(3_000L + gapMs), 0))
        fix(at(2_004.2), atElapsedMs = 3_000L + gapMs)

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertTrue(state.doubtful)
        assertTrue(state.bridgedMeters > 1_900.0)
    }

    /** Steps that arrive late — the watch batches — still vouch for ground already judged. */
    @Test
    fun `steps arriving after a stretch is judged change its verdict`() {
        val gapMs = 600_000L
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(4.2), atElapsedMs = 3_000L)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(0L), epoch(3_000L), 4))
        // The counter's report for the gap hasn't landed yet: no evidence, judged
        // on speed alone, and 3.3 m/s with nothing against it counts.
        fix(at(2_004.2), atElapsedMs = 3_000L + gapMs)
        TrackingManager.onSteps(StepInterval(StepSource.PHONE, epoch(3_000L), epoch(3_000L + gapMs), 0))

        assertTrue("Zero steps across it, once known, sets it aside", TrackingManager.walk.value.doubtful)
    }

    // --- helpers -------------------------------------------------------------

    private fun startWalking() =
        TrackingManager.startWalk(relaxedThresholds = false, activityType = ActivityType.WALK)

    private fun fix(
        point: LatLng,
        atElapsedMs: Long,
        accuracyMeters: Float? = 5f,
    ) = TrackingManager.onLocation(
        point = point,
        accuracyMeters = accuracyMeters,
        // No reported speed: the manager derives it from the points and their
        // timestamps, which is exactly the calculation under test.
        speedMps = null,
        atElapsedMs = atElapsedMs,
        motion = null,
        altitudeMeters = null,
    )

    /**
     * The wall-clock time the manager gives a fix taken at [elapsedMs]: fixes
     * here carry no epoch of their own, so it carries the monotonic clock onto
     * the wall clock from the first one. Steps have to be on the same clock.
     */
    private fun epoch(elapsedMs: Long): Long =
        TrackingManager.walk.value.track.first().atEpochMs!! + elapsedMs

    /** [meters] north of the fixed origin. */
    private fun at(meters: Double) = north(ATHENS, meters)

    private fun north(from: LatLng, meters: Double) =
        LatLng(from.lat + meters / 111_195.0, from.lng)

    private fun east(from: LatLng, meters: Double) =
        LatLng(from.lat, from.lng + meters / (111_195.0 * 0.788)) // cos(37.98°)

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
    }
}
