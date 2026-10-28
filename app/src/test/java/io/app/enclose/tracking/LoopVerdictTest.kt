package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The verdict at Stop, on the scenarios the plan set out. Each honest walk here
 * used to be ended by the app, and each cheat still has to fail: these pin where
 * the line between them now falls.
 */
class LoopVerdictTest {

    // --- honest walks that used to end early ----------------------------------

    @Test
    fun `a 10k walk the classifier calls a vehicle for four minutes is claimed whole`() {
        val claim = tenK(cadence = WALKING_CADENCE).verdict().claim()

        assertTrue("Walking pace needs no proof", claim.split.bridges.isEmpty())
        assertEquals(7_500.0, claim.split.onFootMeters, 20.0)
        assertTrue("About ten thousand steps: ${claim.split.phoneSteps}", claim.split.phoneSteps!! in 9_000..10_500)
    }

    @Test
    fun `the same walk on a phone with no step sensor is still claimed`() {
        val claim = tenK(cadence = null).verdict().claim()

        assertTrue(claim.split.bridges.isEmpty())
        assertNull("No sensor is no count, not zero", claim.split.phoneSteps)
    }

    @Test
    fun `a 1 km tram ride in a 10k loop is claimed with a bridge of about 13 percent`() {
        val top = north(ATHENS, SIDE)
        val claim = Route()
            .to(top)
            .to(east(top, 400.0))
            // Under three minutes sitting on a tram: fast, and no steps.
            .to(east(top, 1_400.0), mps = 6.0, cadence = 0.0)
            .to(east(top, SIDE))
            .to(east(ATHENS, SIDE))
            .to(ATHENS)
            .verdict()
            .claim()

        val bridge = claim.split.bridges.single()
        assertEquals(1_000.0, bridge.meters, 80.0)
        assertNotNull("A bridge says when it was", bridge.fromEpochMs)
        assertTrue("About 13%: ${claim.bridgedShare}", claim.bridgedShare in 0.12..0.16)
        // The bridged ring replaces the ride with its two ends.
        assertTrue(claim.ring.size < claim.endIndex - claim.startIndex + 1)
    }

    @Test
    fun `a drive to the park before the walk is no part of the loop`() {
        val park = north(ATHENS, 6_000.0)
        val corner = north(park, 300.0)
        val claim = Route()
            // Pressed Start, then ten minutes in the car.
            .to(park, mps = 10.0, cadence = 0.0)
            .to(corner)
            .to(east(corner, 300.0))
            .to(east(park, 300.0))
            .to(park)
            .verdict()
            .claim()

        assertTrue(claim.startIndex > 0)
        assertTrue("Starts where the walking did", Geo.distanceMeters(park, claim.ring.first()) < 40.0)
        assertTrue("A leading drive is not bridged", claim.split.bridges.isEmpty())
        assertEquals(1_200.0, Geo.pathLengthMeters(claim.ring), 60.0)
    }

    @Test
    fun `stop 2 km past the start claims the loop closed earlier`() {
        val route = Route()
            .to(north(ATHENS, 500.0))
            .to(east(north(ATHENS, 500.0), 500.0))
            .to(east(ATHENS, 500.0))
            .to(ATHENS)
        val closedAt = route.path.lastIndex
        // Forgot to press Stop, and went on home.
        route.to(east(ATHENS, -2_000.0))

        val claim = route.verdict(atStart = false).claim()

        assertEquals(closedAt, claim.endIndex)
        assertEquals(ATHENS, claim.ring.first())
        assertEquals(2_000.0, Geo.pathLengthMeters(claim.ring), 10.0)
    }

    @Test
    fun `stopped short of the start with no loop behind it is kept, not closed`() {
        val kept = Route()
            .to(north(ATHENS, 500.0))
            .to(east(north(ATHENS, 500.0), 500.0))
            .to(east(ATHENS, 500.0))
            .to(east(ATHENS, 40.0))
            .verdict(atStart = false)
            .kept()

        assertEquals(UnclaimedReason.NOT_CLOSED, kept.reason)
        assertEquals("Everything walked is in the record", 1_960.0, kept.split.onFootMeters, 10.0)
    }

    @Test
    fun `a dozing phone whose steps cover the gap counts it as walked`() {
        val claim = gapLoop(gapMs = 9 * 60_000L, cadenceAcross = WALKING_CADENCE).verdict().claim()

        assertTrue(claim.split.bridges.isEmpty())
        assertTrue(claim.split.stepsCoveredGap)
        assertFalse("Nothing unobserved to own up to", claim.split.unobservedGap)
    }

    @Test
    fun `a gap with no step data across it counts, and the claim says so`() {
        val claim = gapLoop(gapMs = 9 * 60_000L, cadenceAcross = null).verdict().claim()

        assertTrue(claim.split.bridges.isEmpty())
        assertTrue(claim.split.unobservedGap)
        assertFalse(claim.split.stepsCoveredGap)
    }

    // --- cheats that must still fail ------------------------------------------

    @Test
    fun `location off, a drive, location on - the gap is bridged and counted`() {
        // Six minutes in a car with location off; the counter kept running and
        // counted nothing.
        val kept = gapLoop(gapMs = 6 * 60_000L, cadenceAcross = 0.0).verdict().kept()

        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, kept.reason)
        assertEquals("The gap is the bridge", 1_500.0, kept.split.bridges.single().meters, 2.0)
        assertEquals(0.30, kept.bridgedShare, 0.01)
    }

    @Test
    fun `half a loop driven is saved without a claim`() {
        val kept = Route()
            .to(north(ATHENS, 500.0))
            .to(east(north(ATHENS, 500.0), 500.0), mps = 12.0, cadence = 0.0)
            .to(east(ATHENS, 500.0), mps = 12.0, cadence = 0.0)
            .to(ATHENS)
            .verdict()
            .kept()

        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, kept.reason)
        assertTrue(kept.bridgedShare > LoopVerdict.MAX_BRIDGED_SHARE)
        assertEquals(1, kept.split.bridges.size)
        assertEquals("The walking is still on record", 1_000.0, kept.split.onFootMeters, 100.0)
    }

    @Test
    fun `walk out, drive round a district, walk back - a 1_5 km bridge at 43 percent`() {
        val out = east(north(ATHENS, 661.0), -750.0)
        val back = east(north(ATHENS, 661.0), 750.0)
        val kept = Route()
            .to(out)
            .to(north(out, 1_750.0), mps = 12.0, cadence = 0.0)
            .to(north(back, 1_750.0), mps = 12.0, cadence = 0.0)
            .to(back, mps = 12.0, cadence = 0.0)
            .to(ATHENS)
            .verdict()
            .kept()

        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, kept.reason)
        assertEquals(1_500.0, kept.split.bridges.single().meters, 100.0)
        assertTrue("About 43%: ${kept.bridgedShare}", kept.bridgedShare in 0.38..0.50)
    }

    @Test
    fun `a whole loop driven claims nothing`() {
        val corner = north(ATHENS, 1_000.0)
        val kept = Route()
            .to(corner, mps = 12.0, cadence = 0.0)
            .to(east(corner, 1_000.0), mps = 12.0, cadence = 0.0)
            .to(east(ATHENS, 1_000.0), mps = 12.0, cadence = 0.0)
            .to(ATHENS, mps = 12.0, cadence = 0.0)
            .verdict()
            .kept()

        assertEquals(UnclaimedReason.TOO_MUCH_BRIDGED, kept.reason)
        assertEquals("Nothing was walked", 1.0, kept.bridgedShare, 0.0)
        assertEquals(0.0, kept.split.onFootMeters, 1.0)
    }

    @Test
    fun `a route with no GPS behind it is never judged`() {
        // A GPX replay or map taps: the same fast route, but unjudged.
        val corner = north(ATHENS, 1_000.0)
        val claim = Route()
            .to(corner, mps = 12.0, cadence = 0.0)
            .to(east(corner, 1_000.0), mps = 12.0, cadence = 0.0)
            .to(east(ATHENS, 1_000.0), mps = 12.0, cadence = 0.0)
            .to(ATHENS, mps = 12.0, cadence = 0.0)
            .verdict(judged = false)
            .claim()

        assertTrue(claim.split.bridges.isEmpty())
    }

    @Test
    fun `the stop dialog's earlier loop is the verdict's`() {
        val route = Route()
            .to(north(ATHENS, 500.0))
            .to(east(north(ATHENS, 500.0), 500.0))
            .to(east(ATHENS, 500.0))
            .to(ATHENS)
            .to(east(ATHENS, -300.0))

        val end = LoopVerdict.loopEnd(
            route.path,
            startIndex = 0,
            atStart = false,
            closeRadiusMeters = TrackingManager.CLOSURE_RADIUS_METERS,
            leaveRadiusMeters = TrackingManager.LEAVE_START_RADIUS_METERS,
            minPerimeterMeters = TrackingManager.MIN_PERIMETER_METERS,
        )

        assertEquals(route.verdict(atStart = false).claim().endIndex, end)
    }

    // --- helpers -------------------------------------------------------------

    /** A square loop of about ten thousand steps, four minutes of it read as a vehicle. */
    private fun tenK(cadence: Double?): Route {
        val top = north(ATHENS, SIDE)
        val vehicleFor = 4 * 60 * WALKING_MPS
        return Route()
            .to(top, cadence = cadence)
            .to(east(top, 600.0), cadence = cadence)
            .to(east(top, 600.0 + vehicleFor), cadence = cadence, motion = MotionEvidence.VEHICLE)
            .to(east(top, SIDE), cadence = cadence)
            .to(east(ATHENS, SIDE), cadence = cadence)
            .to(ATHENS, cadence = cadence)
    }

    /**
     * 1 km north, a signal gap 1.5 km east over [gapMs], then back: a 5 km loop
     * with one straight line nobody observed. [cadenceAcross] is the phone's
     * count across the gap, or null for none at all.
     */
    private fun gapLoop(gapMs: Long, cadenceAcross: Double?): Route {
        val top = north(ATHENS, 1_000.0)
        return Route()
            .to(top)
            .gap(east(top, 1_500.0), gapMs, cadenceAcross)
            .to(east(ATHENS, 1_500.0))
            .to(ATHENS)
    }

    /** A recorded route, built leg by leg: a fix every three seconds, each with its time. */
    private class Route {
        val path = mutableListOf(ATHENS)
        val track = mutableListOf(TrackPoint(atEpochMs = T0))
        val steps = mutableListOf<StepInterval>()
        private var t = T0

        /**
         * A straight line to [target] at [mps]. The phone counts [cadence] steps
         * a second along it, or nothing at all when null — no sensor, no report.
         */
        fun to(
            target: LatLng,
            mps: Double = WALKING_MPS,
            cadence: Double? = WALKING_CADENCE,
            motion: MotionEvidence? = null,
        ) = apply {
            val from = path.last()
            val began = t
            val fixes = ceil(Geo.distanceMeters(from, target) / (mps * FIX_MS / 1000.0)).toInt().coerceAtLeast(1)
            for (k in 1..fixes) {
                val f = k.toDouble() / fixes
                path += LatLng(from.lat + (target.lat - from.lat) * f, from.lng + (target.lng - from.lng) * f)
                t += FIX_MS
                track += TrackPoint(atEpochMs = t, motion = motion)
            }
            count(began, cadence)
        }

        /** Nothing recorded for [ms], then a fix at [target]. */
        fun gap(target: LatLng, ms: Long, cadence: Double?) = apply {
            val began = t
            path += target
            t += ms
            track += TrackPoint(atEpochMs = t, afterGap = true)
            count(began, cadence)
        }

        private fun count(began: Long, cadence: Double?) {
            if (cadence == null) return
            steps += StepInterval(StepSource.PHONE, began, t, ((t - began) / 1000.0 * cadence).roundToInt())
        }

        fun verdict(atStart: Boolean = true, judged: Boolean = true) = LoopVerdict.of(
            path = path,
            track = track,
            steps = steps,
            activityType = ActivityType.WALK,
            judged = judged,
            atStart = atStart,
            closeRadiusMeters = TrackingManager.CLOSURE_RADIUS_METERS,
            leaveRadiusMeters = TrackingManager.LEAVE_START_RADIUS_METERS,
            minPerimeterMeters = TrackingManager.MIN_PERIMETER_METERS,
        )
    }

    private fun LoopVerdict.Result.claim(): LoopVerdict.Claim =
        this as? LoopVerdict.Claim
            ?: throw AssertionError("Expected a claim, but it was kept as ${(this as LoopVerdict.Unclaimed).reason}")

    private fun LoopVerdict.Result.kept(): LoopVerdict.Unclaimed =
        this as? LoopVerdict.Unclaimed ?: throw AssertionError("Expected no claim, but it was claimed")

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
        const val T0 = 1_700_000_000_000L
        const val FIX_MS = 3_000L
        const val WALKING_MPS = 1.4
        const val WALKING_CADENCE = 1.8

        /** A square of this side is a 7.5 km loop: about ten thousand steps. */
        const val SIDE = 1_875.0

        fun north(from: LatLng, meters: Double) = LatLng(from.lat + meters / 111_195.0, from.lng)

        fun east(from: LatLng, meters: Double) =
            LatLng(from.lat, from.lng + meters / (111_195.0 * 0.788)) // cos(37.98°)
    }
}
