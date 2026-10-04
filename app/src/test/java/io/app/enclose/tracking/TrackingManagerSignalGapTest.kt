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
 * other side of it — a walk that really was *seen* moving in a vehicle still has
 * to answer for the ground it covered.
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
        assertFalse("Silence is not a speeding offence", state.motionBlocked)
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
        assertFalse(state.motionBlocked)
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
        assertFalse(state.motionBlocked)
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
    fun `a wildly inaccurate fix cannot void the walk`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(10.0), atElapsedMs = 3_000L)

        // Reacquiring after signal loss: 800 m out, and the provider says so.
        // Fed to the speed window this alone used to be enough to void a walk.
        fix(at(800.0), atElapsedMs = 6_000L, accuracyMeters = 400f)

        val state = TrackingManager.walk.value
        assertTrue(state.isTracking)
        assertFalse("A fix that vague judges nothing", state.motionBlocked)
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

    @Test
    fun `a jump the next fix agrees with is kept`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)
        fix(at(5.0), atElapsedMs = 3_000L)
        fix(at(300.0), atElapsedMs = 6_000L)
        assertEquals("Held until the next fix says what it was", 2, TrackingManager.walk.value.path.size)

        fix(at(305.0), atElapsedMs = 9_000L)

        val state = TrackingManager.walk.value
        assertEquals(listOf(at(0.0), at(5.0), at(300.0), at(305.0)), state.path)
        assertTrue("The jump crossed ground nobody observed; say so", state.hadSignalGap)
    }

    @Test
    fun `a spike that got through is cut from the claim`() {
        startWalking()
        // Two bad fixes in a row agree with each other, so the live hold keeps
        // them; the cleanup at Stop is what takes them off the claim.
        var t = 0L
        val square = listOf(at(0.0), at(100.0), east(at(100.0), 100.0), east(at(0.0), 100.0))
        for (corner in square) {
            fix(corner, atElapsedMs = t)
            t += 100_000L
        }
        // Back up the east side; then two bad fixes that agree with each other.
        t -= 50_000L
        fix(east(at(50.0), 100.0), atElapsedMs = t)
        fix(east(at(50.0), 400.0), atElapsedMs = t + 3_000L) // spike...
        fix(east(at(52.0), 400.0), atElapsedMs = t + 6_000L) // ...and its twin
        fix(east(at(54.0), 100.0), atElapsedMs = t + 9_000L) // back on the path
        fix(east(at(58.0), 100.0), atElapsedMs = t + 12_000L)
        fix(east(at(0.0), 100.0), atElapsedMs = t + 60_000L)
        fix(at(0.0), atElapsedMs = t + 140_000L)

        TrackingManager.finishWalk()

        val ring = TrackingManager.pendingClaim.value!!.ring
        assertFalse(ring.contains(east(at(50.0), 400.0)))
        assertFalse(ring.contains(east(at(52.0), 400.0)))
    }

    // --- the anti-cheat side, which must not have been loosened ---------------

    @Test
    fun `sustained driving speed still blocks and then voids`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        // 25 m/s ≈ 90 km/h, every 3 s: fast enough that no declared or detected
        // activity justifies it, and sustained through every warning the walk has.
        var metres = 0.0
        var t = 0L
        repeat(150) {
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }

        assertFalse(
            "A drive must not be able to enclose territory",
            TrackingManager.walk.value.isTracking,
        )
    }

    /**
     * The loosening, from the other side: a minute of movement the gate doesn't
     * like is a warning, not the end of the outing. Three of those is roughly
     * four and a half minutes, which is why the test above has to drive so far.
     */
    @Test
    fun `a short blocked stretch no longer ends the walk`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        var metres = 0.0
        var t = 0L
        repeat(20) { // 60 s at vehicle speed — inside the first grace window
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }

        val state = TrackingManager.walk.value
        assertTrue("A minute of bad movement must not cost the walk", state.isTracking)
        assertTrue("...but it must be visibly not recording", state.motionBlocked)
        assertEquals("...and it hasn't cost a warning yet", 0, state.strikes)
    }

    @Test
    fun `the walk counts its warnings`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        var metres = 0.0
        var t = 0L
        repeat(40) { // past the first grace window, not past the second
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }

        val state = TrackingManager.walk.value
        assertTrue("One warning is survivable", state.isTracking)
        assertEquals(1, state.strikes)
        assertEquals(2, state.strikesRemaining)
    }

    @Test
    fun `losing the signal mid-block costs a warning`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        // Blocked: seen moving at vehicle speed, but not yet past the grace.
        var metres = 0.0
        var t = 0L
        repeat(3) {
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }
        assertTrue("Should be blocked, not yet void", TrackingManager.walk.value.motionBlocked)

        // Now the signal drops, and recording picks up far from where it stopped.
        // The gap reset clears the *speed window*, so this fix reads as slow —
        // but the ground covered while blocked is still unaccounted for.
        fix(at(metres + 500.0), atElapsedMs = t + 120_000L)

        val state = TrackingManager.walk.value
        assertTrue("Half a kilometre is a warning, not an ending", state.isTracking)
        assertEquals("...and it has to cost one", 1, state.strikes)
        assertTrue(
            "The route now bridges ground nobody recorded; say so",
            state.hadSignalGap,
        )
    }

    /**
     * The other side of the loosening. A warning covers a gap the recording could
     * plausibly have missed; it does not cover a drive across town, because the
     * loop drawn round it would enclose ground nobody walked.
     */
    @Test
    fun `a gap too big for any warning still voids the walk`() {
        startWalking()
        fix(at(0.0), atElapsedMs = 0L)

        var metres = 0.0
        var t = 0L
        repeat(3) {
            metres += 75.0
            t += 3_000L
            fix(at(metres), atElapsedMs = t)
        }
        assertTrue(TrackingManager.walk.value.motionBlocked)

        fix(at(metres + 4_000.0), atElapsedMs = t + 120_000L)

        assertFalse(
            "Four kilometres of unrecorded ground is a drive, whatever the warnings say",
            TrackingManager.walk.value.isTracking,
        )
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
