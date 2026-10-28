package io.app.enclose.tracking

import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How each stretch of a walk counts. The old motion gate's mistakes ended walks
 * in the band where walking and slow traffic meet; these pin that the band is
 * now free, that steps are what vouch for anything faster, and that nothing a
 * walker carries can vouch past the ceiling.
 */
class StretchJudgeTest {

    // --- judging one stretch --------------------------------------------------

    @Test
    fun `walking pace needs no proof, whatever the classifier says`() {
        val stretch = judge(meters = 75.0, steps = 0, vehicle = true)
        assertEquals(StretchLabel.ON_FOOT, stretch.label)
        assertNull(stretch.reason)
    }

    @Test
    fun `past walking pace, the steps decide`() {
        // 76 m in 30 s: 38 steps of 2 m cover it, 37 don't.
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 76.0, steps = 38).label)
        val short = judge(meters = 76.0, steps = 37)
        assertEquals(StretchLabel.DOUBTFUL, short.label)
        assertEquals(DoubtReason.TOO_FAST, short.reason)
    }

    @Test
    fun `with no step data a stretch counts unless the classifier said vehicle`() {
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 120.0, steps = null).label)
        val car = judge(meters = 120.0, steps = null, vehicle = true)
        assertEquals(StretchLabel.DOUBTFUL, car.label)
        assertEquals(DoubtReason.VEHICLE, car.reason)
    }

    @Test
    fun `steps can't vouch past the walk's ceiling`() {
        val stretch = judge(meters = ActivityType.WALK.maxSpeedMps * 30 + 3, steps = 10_000)
        assertEquals(StretchLabel.DOUBTFUL, stretch.label)
        assertEquals(DoubtReason.TOO_FAST, stretch.reason)
    }

    @Test
    fun `a confident run raises a walk's ceiling`() {
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 180.0, detected = MotionEvidence.RUNNING).label)
        assertEquals(StretchLabel.DOUBTFUL, judge(meters = 180.0).label)
    }

    @Test
    fun `a vehicle reading stops the ceiling rising`() {
        val stretch = judge(meters = 180.0, detected = MotionEvidence.RUNNING, vehicle = true)
        assertEquals(StretchLabel.DOUBTFUL, stretch.label)
        assertEquals(DoubtReason.VEHICLE, stretch.reason)
    }

    @Test
    fun `a confirmed ride needs no steps`() {
        // A bike takes none, so zero steps is no evidence against it.
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 300.0, steps = 0, detected = MotionEvidence.CYCLING).label)
    }

    @Test
    fun `a declared ride is never asked for steps, but a vehicle still counts against it`() {
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 270.0, steps = 0, declared = ActivityType.BIKE).label)
        assertEquals(StretchLabel.DOUBTFUL, judge(meters = 270.0, vehicle = true, declared = ActivityType.BIKE).label)
    }

    @Test
    fun `nothing raises a ceiling past the absolute`() {
        val stretch = judge(
            meters = StretchJudge.ABSOLUTE_MAX_SPEED_MPS * 30 + 3,
            detected = MotionEvidence.CYCLING,
            declared = ActivityType.BIKE,
        )
        assertEquals(StretchLabel.DOUBTFUL, stretch.label)
        assertEquals(DoubtReason.TOO_FAST, stretch.reason)
    }

    @Test
    fun `a gap counts as unobserved unless steps cover it`() {
        val unseen = judge(meters = 600.0, seconds = 600, gap = true)
        assertEquals(StretchLabel.UNOBSERVED, unseen.label)
        assertTrue(unseen.gap)

        val walked = judge(meters = 600.0, seconds = 600, steps = 300, gap = true)
        assertEquals(StretchLabel.ON_FOOT, walked.label)
        assertTrue(walked.gap)
    }

    @Test
    fun `a gap too fast to have been walked is doubtful, steps or not`() {
        assertEquals(StretchLabel.DOUBTFUL, judge(meters = 2_000.0, seconds = 300, steps = 5_000, gap = true).label)
    }

    @Test
    fun `no time to measure a speed over counts`() {
        assertEquals(StretchLabel.ON_FOOT, judge(meters = 50.0, seconds = 0).label)
    }

    // --- cutting a walk into stretches ---------------------------------------

    @Test
    fun `a walk is cut into thirty-second stretches`() {
        val (path, track) = line(segments = 30)
        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertEquals(listOf(0 to 10, 10 to 20, 20 to 30), stretches.map { it.fromIndex to it.toIndex })
        assertTrue(stretches.all { it.label == StretchLabel.ON_FOOT })
    }

    @Test
    fun `live, the stretch still being walked is left unjudged`() {
        val (path, track) = line(segments = 35)
        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = false)

        assertEquals(30, stretches.last().toIndex)
    }

    @Test
    fun `at stop a short tail is judged with the stretch before it`() {
        val (path, track) = line(segments = 34)
        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertEquals(listOf(0 to 10, 10 to 20, 20 to 34), stretches.map { it.fromIndex to it.toIndex })
    }

    @Test
    fun `at stop a long tail stands on its own`() {
        val (path, track) = line(segments = 36)
        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertEquals(30 to 36, stretches.last().let { it.fromIndex to it.toIndex })
    }

    @Test
    fun `GPS wander is judged on where it got to, not how far it zig-zagged`() {
        // Between tall buildings: each fix lands 12 m either side of the line
        // walked, so fix to fix reads about 8 m/s. Over thirty seconds it got 42 m.
        val path = (0..30).map { i -> east(north(ATHENS, i * 4.2), if (i % 2 == 0) 12.0 else -12.0) }
        val track = path.indices.map { TrackPoint(atEpochMs = T0 + it * 3_000L) }
        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertTrue(stretches.all { it.label == StretchLabel.ON_FOOT })
    }

    @Test
    fun `a signal gap is judged whole, on its own`() {
        val (before, beforeTrack) = line(segments = 10)
        val after = north(before.last(), 600.0)
        val path = before + after + (1..10).map { north(after, it * 4.2) }
        val gapEnd = beforeTrack.last().atEpochMs!! + 600_000L
        val track = beforeTrack +
            TrackPoint(atEpochMs = gapEnd, afterGap = true) +
            (1..10).map { TrackPoint(atEpochMs = gapEnd + it * 3_000L) }

        val stretches = StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertEquals(listOf(0 to 10, 10 to 11, 11 to 21), stretches.map { it.fromIndex to it.toIndex })
        assertEquals(StretchLabel.UNOBSERVED, stretches[1].label)
        assertTrue(stretches[1].gap)
    }

    @Test
    fun `points with no clock behind them are never judged`() {
        // Map taps, or a GPX replay: no times, so no speed to judge by.
        val path = (0..4).map { north(ATHENS, it * 500.0) }
        val stretches = StretchJudge.stretches(path, emptyList(), StepLog(emptyList()), ActivityType.WALK, complete = true)

        assertEquals(4, stretches.size)
        assertTrue(stretches.all { it.label == StretchLabel.ON_FOOT })
    }

    @Test
    fun `the watch can only help`() {
        // 4 m/s: past walking pace, so the steps decide.
        val (path, track) = line(segments = 10, metersApart = 12.0)
        fun label(vararg steps: StepInterval) =
            StretchJudge.stretches(path, track, StepLog(steps.toList()), ActivityType.WALK, complete = true)
                .single().label

        val phoneNone = interval(StepSource.PHONE, 0)
        val phoneEnough = interval(StepSource.PHONE, 70)
        val watchNone = interval(StepSource.WATCH, 0)
        val watchEnough = interval(StepSource.WATCH, 70)
        assertEquals(StretchLabel.DOUBTFUL, label(phoneNone))
        assertEquals("The watch's steps vouch for it", StretchLabel.ON_FOOT, label(phoneNone, watchEnough))
        assertEquals("A watch that counted nothing costs nothing", StretchLabel.ON_FOOT, label(phoneEnough, watchNone))
    }

    @Test
    fun `a stretch reads as a vehicle when half its fixes said so`() {
        val (path, plain) = line(segments = 10, metersApart = 12.0)
        fun label(vehicleFixes: Int): StretchLabel {
            val track = plain.mapIndexed { i, p -> if (i in 1..vehicleFixes) p.copy(motion = MotionEvidence.VEHICLE) else p }
            return StretchJudge.stretches(path, track, StepLog(emptyList()), ActivityType.WALK, complete = true)
                .single().label
        }

        assertEquals(StretchLabel.DOUBTFUL, label(vehicleFixes = 5))
        assertEquals(StretchLabel.ON_FOOT, label(vehicleFixes = 4))
    }

    // --- where the walk begins, and what is bridged --------------------------

    @Test
    fun `a drive at the start moves the start to where the walking began`() {
        val path = listOf(ATHENS, north(ATHENS, 300.0), north(ATHENS, 600.0), north(ATHENS, 610.0))
        val stretches = listOf(doubtful(0, 2), onFoot(2, 3))

        assertEquals(2, StretchJudge.walkStart(stretches, path, minDriveMeters = 80.0))
    }

    @Test
    fun `a wobble in the first fixes doesn't move the start`() {
        val path = listOf(ATHENS, north(ATHENS, 30.0), north(ATHENS, 50.0), north(ATHENS, 60.0))
        val stretches = listOf(doubtful(0, 2), onFoot(2, 3))

        assertEquals(0, StretchJudge.walkStart(stretches, path, minDriveMeters = 80.0))
    }

    @Test
    fun `a walk that began on foot starts at its first point`() {
        val path = listOf(ATHENS, north(ATHENS, 300.0), north(ATHENS, 600.0))
        val stretches = listOf(onFoot(0, 1), doubtful(1, 2))

        assertEquals(0, StretchJudge.walkStart(stretches, path, minDriveMeters = 80.0))
    }

    @Test
    fun `while nothing has been walked the start is where the doubt ends`() {
        val path = listOf(ATHENS, north(ATHENS, 300.0), north(ATHENS, 600.0))
        val stretches = listOf(doubtful(0, 1), doubtful(1, 2))

        assertEquals(2, StretchJudge.walkStart(stretches, path, minDriveMeters = 80.0))
    }

    @Test
    fun `doubtful stretches that touch are one run`() {
        val stretches = listOf(doubtful(0, 10), doubtful(10, 20), onFoot(20, 30), doubtful(30, 40))

        assertEquals(listOf(0..20, 30..40), StretchJudge.doubtfulRuns(stretches))
    }

    @Test
    fun `runs are counted from the start, and one across it is cut there`() {
        val stretches = listOf(doubtful(0, 10), doubtful(10, 20), onFoot(20, 30), doubtful(30, 40))

        assertEquals(listOf(15..20, 30..40), StretchJudge.doubtfulRuns(stretches, fromIndex = 15))
        assertEquals(listOf(30..40), StretchJudge.doubtfulRuns(stretches, fromIndex = 20))
    }

    // --- the walker's own stride ----------------------------------------------

    @Test
    fun `a walker's step is learned from the walking their steps cover`() {
        // Ten minutes at 1.4 m/s, 0.7 m a step.
        val (path, track) = line(segments = 200)
        val steps = StepLog(listOf(StepInterval(StepSource.PHONE, T0, T0 + 600_000L, 1_200)))
        val stretches = StretchJudge.stretches(path, track, steps, ActivityType.WALK, complete = true)
        assertEquals(0.7, StretchJudge.learnedStepLength(path, track, steps, stretches)!!, 0.01)
    }

    @Test
    fun `too little walking teaches no stride`() {
        val (path, track) = line(segments = 50)
        val steps = StepLog(listOf(StepInterval(StepSource.PHONE, T0, T0 + 150_000L, 300)))
        val stretches = StretchJudge.stretches(path, track, steps, ActivityType.WALK, complete = true)
        assertNull(StretchJudge.learnedStepLength(path, track, steps, stretches))
    }

    @Test
    fun `a gap's limit never loosens, and has a floor`() {
        assertEquals(StretchJudge.MAX_STEP_LENGTH_M, StretchJudge.gapStepLength(null), 0.0)
        assertEquals("A long stride keeps the plain limit", StretchJudge.MAX_STEP_LENGTH_M, StretchJudge.gapStepLength(1.2), 0.0)
        assertEquals(0.7 * StretchJudge.GAP_STEP_FACTOR, StretchJudge.gapStepLength(0.7), 1e-9)
        assertEquals(StretchJudge.MIN_GAP_STEP_LENGTH_M, StretchJudge.gapStepLength(0.4), 0.0)
    }

    @Test
    fun `a gap is held to the walker's own stride`() {
        // Ten minutes walked at 0.7 m a step, then ten minutes unseen covering 2 km.
        val (walked, walkedTrack) = line(segments = 200)
        val gapEnd = walkedTrack.last().atEpochMs!! + 600_000L
        val path = walked + north(walked.last(), 2_000.0)
        val track = walkedTrack + TrackPoint(atEpochMs = gapEnd, afterGap = true)
        fun gapLabel(gapSteps: Int): StretchLabel {
            val steps = StepLog(
                listOf(
                    StepInterval(StepSource.PHONE, T0, T0 + 600_000L, 1_200),
                    StepInterval(StepSource.PHONE, T0 + 600_000L, gapEnd, gapSteps),
                ),
            )
            return StretchJudge.stretches(path, track, steps, ActivityType.WALK, complete = true)
                .single { it.gap }.label
        }
        // 1.8 m a step passes at two metres; from someone whose step is 0.7 m it
        // is a short walk and a ride.
        assertEquals(StretchLabel.DOUBTFUL, gapLabel(1_100))
        // 1.2 m a step is a jog, and still counts.
        assertEquals(StretchLabel.ON_FOOT, gapLabel(1_650))
    }

    @Test
    fun `without a stride to go on, a gap keeps the plain limit`() {
        val path = listOf(ATHENS, north(ATHENS, 2_000.0))
        val track = listOf(TrackPoint(atEpochMs = T0), TrackPoint(atEpochMs = T0 + 600_000L, afterGap = true))
        val steps = StepLog(listOf(StepInterval(StepSource.PHONE, T0, T0 + 600_000L, 1_100)))
        assertEquals(
            StretchLabel.ON_FOOT,
            StretchJudge.stretches(path, track, steps, ActivityType.WALK, complete = true).single().label,
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun judge(
        meters: Double,
        seconds: Int = 30,
        steps: Int? = null,
        vehicle: Boolean = false,
        detected: MotionEvidence? = null,
        gap: Boolean = false,
        declared: ActivityType = ActivityType.WALK,
    ) = StretchJudge.judge(meters, seconds * 1_000L, steps, vehicle, detected, gap, declared)

    /** [segments] fixes after the first, three seconds and [metersApart] apart, heading north. */
    private fun line(segments: Int, metersApart: Double = 4.2): Pair<List<LatLng>, List<TrackPoint>> {
        val path = (0..segments).map { north(ATHENS, it * metersApart) }
        return path to path.indices.map { TrackPoint(atEpochMs = T0 + it * 3_000L) }
    }

    /** One report over the first thirty seconds. */
    private fun interval(source: StepSource, steps: Int) = StepInterval(source, T0, T0 + 30_000L, steps)

    private fun doubtful(from: Int, to: Int) = Stretch(from, to, StretchLabel.DOUBTFUL, DoubtReason.TOO_FAST)

    private fun onFoot(from: Int, to: Int) = Stretch(from, to, StretchLabel.ON_FOOT)

    private fun north(from: LatLng, meters: Double) = LatLng(from.lat + meters / 111_195.0, from.lng)

    private fun east(from: LatLng, meters: Double) =
        LatLng(from.lat, from.lng + meters / (111_195.0 * 0.788)) // cos(37.98°)

    private companion object {
        val ATHENS = LatLng(37.9838, 23.7275)
        const val T0 = 1_700_000_000_000L
    }
}
