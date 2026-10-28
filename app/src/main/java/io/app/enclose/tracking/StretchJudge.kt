package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/** How one stretch of a walk counts towards its claim. */
enum class StretchLabel {
    /** Covered under the walker's own power: counts. */
    ON_FOOT,

    /**
     * A signal gap with no step data across it, slow enough to have been walked.
     * It counts, as gaps always have, and the claim says part of its outline is
     * a straight line across ground nobody observed.
     */
    UNOBSERVED,

    /**
     * Nothing vouches for it being walked. It is still recorded, and the walk
     * carries on; in the claim it becomes a straight line between its two ends,
     * so a detour by car or tram adds no area.
     */
    DOUBTFUL,
}

/** Why a stretch is doubtful — for the wording, never for the verdict. */
enum class DoubtReason {
    /** The classifier confidently reported a vehicle. */
    VEHICLE,

    /** Faster than anything the walk's activity — declared or detected — explains. */
    TOO_FAST,
}

/**
 * One stretch of a recorded walk and how it counts. [fromIndex] and [toIndex]
 * index the path, inclusive; consecutive stretches share their joining point.
 */
data class Stretch(
    val fromIndex: Int,
    val toIndex: Int,
    val label: StretchLabel,
    /** Non-null exactly when [label] is [StretchLabel.DOUBTFUL]. */
    val reason: DoubtReason? = null,
    /**
     * A signal gap judged whole: the single straight line between two fixes. One
     * labelled [StretchLabel.ON_FOOT] is a gap the steps covered.
     */
    val gap: Boolean = false,
)

/**
 * Decides how each stretch of a walk counts. This replaces the old motion gate,
 * and the difference is the whole point: the gate judged every fix, stopped
 * recording what it disliked and ended the walk after three warnings, which is
 * how four minutes of a wrong guess from the classifier threw away more than an
 * hour of walking. Nothing here ends a walk or stops recording. It labels.
 *
 * A walk is cut into [STRETCH_MS] stretches. Each is judged on its **net
 * displacement over its time**, so GPS wander, which zig-zags in place, can't
 * add speed the way a fix-to-fix average let it. A signal gap is judged whole,
 * as one stretch: a phone that dozed for ten minutes is judged on ten minutes,
 * and on the steps taken in them.
 *
 * The rules, slowest first:
 *
 *  - **Walking pace needs no proof.** At or below [FREE_SPEED_MPS] a stretch
 *    counts, whatever the classifier says. A car at walking pace gains nothing
 *    over walking, and this band is where the classifier's mistakes used to end
 *    real walks.
 *  - **Steps are the proof for anything faster**, up to the walk's ceiling: the
 *    declared activity's, raised (never lowered) by a confident run or ride. With
 *    steps from the phone or the watch, the stretch counts if they cover its
 *    distance at up to [MAX_STEP_LENGTH_M] each. With no step data at all it
 *    counts unless the classifier confidently reported a vehicle. A confirmed
 *    ride needs no steps — a bike takes none.
 *  - **Past the ceiling, or past [ABSOLUTE_MAX_SPEED_MPS] whatever anyone says,**
 *    it is doubtful.
 *
 * **A gap is held to the walker's own stride.** Across a signal gap nothing but
 * the steps says how the ground was covered, and at two metres a step — a
 * sprinter's — a short walk inside the silence can vouch for a ride inside the
 * same silence. So once the walk has shown the walker's step
 * ([learnedStepLength]), a gap's steps are taken at up to [GAP_STEP_FACTOR] of it
 * ([gapStepLength]): room for a jog, none for a bus. It never loosens the limit
 * and never tightens it past [MIN_GAP_STEP_LENGTH_M], and a stretch with GPS
 * behind it keeps the plain limit, because its speed is measured, not inferred.
 *
 * A doubtful stretch is not a strike and not an ending: [LoopVerdict] turns it
 * into a straight line at Stop, and only the share of the loop those lines make
 * up decides whether it can be claimed.
 *
 * The watch can only help ([judge] takes the better of the two sources): its
 * steps can turn a doubtful stretch into one on foot and never the reverse, so a
 * watch that is flat, at home or out of range costs nothing.
 *
 * Pure, so every rule is tested on plain numbers.
 */
object StretchJudge {

    /** About ten fixes and fifty-five walking steps. */
    const val STRETCH_MS = 30_000L

    /**
     * 2.5 m/s ≈ 9 km/h: faster than any walk, slower than any drive worth
     * making. At or below it a stretch always counts.
     */
    const val FREE_SPEED_MPS = 2.5

    /** Walking steps are about 0.7 m and running steps reach about 1.8 m. */
    const val MAX_STEP_LENGTH_M = 2.0

    /**
     * A gap's steps are taken at up to this many of the walker's own. A jog is up
     * to about twice a walking step; the rest is room for a long stride, since a
     * gap held too tight is bridged, and bridged ground counts against the claim.
     */
    const val GAP_STEP_FACTOR = 2.2

    /** However short the walker's step, a gap's steps are allowed at least this. */
    const val MIN_GAP_STEP_LENGTH_M = 1.2

    /** About five minutes of walking: enough steps to learn a stride from. */
    const val MIN_LEARNING_STEPS = 600

    /** Slower than this is standing about, where steps say little about stride. */
    const val MIN_LEARNING_SPEED_MPS = 0.5

    /** A ride the classifier confirmed: 16 m/s ≈ 58 km/h covers descents and e-bikes. */
    const val CONFIRMED_CYCLING_MAX_MPS = 16.0

    /**
     * 22 m/s ≈ 79 km/h: nothing human-powered sustains it, so nothing declared
     * or detected raises a ceiling past it.
     */
    const val ABSOLUTE_MAX_SPEED_MPS = 22.0

    /**
     * Judge one stretch.
     *
     * @param steps steps counted across it, or null when no source was counting
     *   across all of it — which is no evidence, never zero.
     * @param vehicle the classifier confidently reported a vehicle for most of it.
     * @param detected a confident run or ride the classifier reported for most of
     *   it, which can raise the ceiling but never lower it.
     * @param gap a signal gap judged whole.
     * @param stepLengthMeters the longest step [steps] are taken at: a gap's is
     *   held to the walker's stride, see [gapStepLength].
     */
    fun judge(
        displacementMeters: Double,
        durationMs: Long,
        steps: Int?,
        vehicle: Boolean,
        detected: MotionEvidence?,
        gap: Boolean,
        declared: ActivityType,
        stepLengthMeters: Double = MAX_STEP_LENGTH_M,
    ): Stretch {
        val stepsCover = steps != null && steps * stepLengthMeters >= displacementMeters
        val counted = if (gap && !stepsCover) StretchLabel.UNOBSERVED else StretchLabel.ON_FOOT
        val doubt = if (vehicle) DoubtReason.VEHICLE else DoubtReason.TOO_FAST
        fun counts() = Stretch(0, 0, counted, gap = gap)
        fun doubtful() = Stretch(0, 0, StretchLabel.DOUBTFUL, reason = doubt, gap = gap)

        // No time to measure a speed over: nothing to judge it by.
        if (durationMs <= 0L) return counts()
        val speed = displacementMeters / (durationMs / 1000.0)

        if (speed <= FREE_SPEED_MPS) return counts()
        if (speed > ABSOLUTE_MAX_SPEED_MPS) return doubtful()

        // A vehicle reading can't lower the ceiling, but it does stop the
        // classifier raising it: a reading that is mostly "car" isn't a ride.
        val raised = when (detected.takeUnless { vehicle }) {
            MotionEvidence.CYCLING -> CONFIRMED_CYCLING_MAX_MPS
            MotionEvidence.RUNNING -> ActivityType.RUN.maxSpeedMps
            MotionEvidence.VEHICLE, null -> 0.0
        }
        val ceiling = maxOf(declared.maxSpeedMps, raised)
        if (speed > ceiling) return doubtful()

        val riding = (detected == MotionEvidence.CYCLING && !vehicle) || !declared.onFoot
        return when {
            // A bike takes no steps, so a ride is never asked for them.
            riding -> if (vehicle) doubtful() else counts()
            steps != null -> if (stepsCover) counts() else doubtful()
            vehicle -> doubtful()
            else -> counts()
        }
    }

    /**
     * Cut a recorded walk into stretches and judge each one.
     *
     * @param complete judge the stretch still in progress too, as at Stop. Live,
     *   the tail is left unjudged until it has run its full [STRETCH_MS].
     */
    fun stretches(
        path: List<LatLng>,
        track: List<TrackPoint>,
        steps: StepLog,
        declared: ActivityType,
        complete: Boolean,
    ): List<Stretch> {
        if (path.size < 2) return emptyList()
        val time = { i: Int -> track.getOrNull(i)?.atEpochMs }
        val out = mutableListOf<Stretch>()

        fun judgeRange(from: Int, to: Int, gap: Boolean, stepLength: Double = MAX_STEP_LENGTH_M): Stretch {
            val a = time(from)
            val b = time(to)
            // No clock at either end (a tap, a GPX point, a point stored before
            // times were kept): no speed to judge by, so it counts.
            if (a == null || b == null) {
                val label = if (gap) StretchLabel.UNOBSERVED else StretchLabel.ON_FOOT
                return Stretch(from, to, label, gap = gap)
            }
            // The fixes that describe this stretch: each one ends a segment of it.
            val evidence = (from + 1..to).map { track.getOrNull(it)?.motion }
            val half = (evidence.size + 1) / 2
            val vehicle = evidence.count { it == MotionEvidence.VEHICLE } >= half
            val cycling = evidence.count { it == MotionEvidence.CYCLING } >= half
            val running = evidence.count { it == MotionEvidence.RUNNING || it == MotionEvidence.CYCLING } >= half
            val detected = when {
                cycling -> MotionEvidence.CYCLING
                running -> MotionEvidence.RUNNING
                else -> null
            }
            val displacement = Geo.distanceMeters(path[from], path[to])
            fun verdict(stepCount: Int?) = judge(
                displacementMeters = displacement,
                durationMs = b - a,
                steps = stepCount,
                vehicle = vehicle,
                detected = detected,
                gap = gap,
                declared = declared,
                stepLengthMeters = stepLength,
            )
            val phone = verdict(steps.stepsBetween(StepSource.PHONE, a, b))
            // The watch can only help: it is asked only when the phone's own
            // evidence left something to improve, and only taken when it does.
            val best = if (phone.label != StretchLabel.ON_FOOT && steps.has(StepSource.WATCH)) {
                val watch = verdict(steps.stepsBetween(StepSource.WATCH, a, b))
                if (watch.label.ordinal < phone.label.ordinal) watch else phone
            } else {
                phone
            }
            return best.copy(fromIndex = from, toIndex = to)
        }

        var i = 0
        for (j in 1 until path.size) {
            val ti = time(i)
            val tj = time(j)
            when {
                track.getOrNull(j)?.afterGap == true -> {
                    if (j - 1 > i) out += judgeRange(i, j - 1, gap = false)
                    out += judgeRange(j - 1, j, gap = true)
                    i = j
                }
                // No clock to cut by: each segment stands alone.
                ti == null || tj == null -> {
                    if (j - 1 > i) out += judgeRange(i, j - 1, gap = false)
                    out += judgeRange(j - 1, j, gap = false)
                    i = j
                }
                tj - ti >= STRETCH_MS -> {
                    out += judgeRange(i, j, gap = false)
                    i = j
                }
            }
        }

        val last = path.lastIndex
        if (complete && last > i) {
            val previous = out.lastOrNull()
            val tailMs = (time(last) ?: 0L) - (time(i) ?: 0L)
            // A few seconds at the end are judged with the stretch before them:
            // over so short a time, ordinary GPS wander reads as speed.
            if (previous != null && !previous.gap && previous.toIndex == i && tailMs < STRETCH_MS / 2) {
                out[out.lastIndex] = judgeRange(previous.fromIndex, last, gap = false)
            } else {
                out += judgeRange(i, last, gap = false)
            }
        }

        // Gaps again, last, held to the stride the rest of the walk has shown —
        // and only looked for when there is a gap to hold to it.
        if (out.any { it.gap }) {
            val stepLength = gapStepLength(learnedStepLength(path, track, steps, out))
            if (stepLength < MAX_STEP_LENGTH_M) {
                for (k in out.indices) {
                    val s = out[k]
                    if (s.gap) out[k] = judgeRange(s.fromIndex, s.toIndex, gap = true, stepLength = stepLength)
                }
            }
        }
        return out
    }

    /**
     * The walker's step as this walk has shown it, in metres: the ground covered
     * over the steps counted, across stretches on foot at walking pace that a
     * count covers. Measured along the path rather than straight across, since
     * GPS wander adds length and an estimate that errs long only loosens the limit
     * it feeds; the longest of the sources' estimates, for the same reason. Null
     * until one source has counted [MIN_LEARNING_STEPS] that way.
     */
    fun learnedStepLength(
        path: List<LatLng>,
        track: List<TrackPoint>,
        steps: StepLog,
        stretches: List<Stretch>,
    ): Double? = StepSource.entries.mapNotNull { source ->
        var meters = 0.0
        var counted = 0
        for (s in stretches) {
            if (s.gap || s.label != StretchLabel.ON_FOOT) continue
            val a = track.getOrNull(s.fromIndex)?.atEpochMs ?: continue
            val b = track.getOrNull(s.toIndex)?.atEpochMs ?: continue
            if (b <= a) continue
            val speed = Geo.distanceMeters(path[s.fromIndex], path[s.toIndex]) / ((b - a) / 1000.0)
            if (speed < MIN_LEARNING_SPEED_MPS || speed > FREE_SPEED_MPS) continue
            val n = steps.stepsBetween(source, a, b)?.takeIf { it > 0 } ?: continue
            meters += Geo.pathLengthMeters(path.subList(s.fromIndex, s.toIndex + 1))
            counted += n
        }
        if (counted >= MIN_LEARNING_STEPS) meters / counted else null
    }.maxOrNull()

    /** The longest step a gap's steps are taken at, given the walker's [learned] one. */
    fun gapStepLength(learned: Double?): Double =
        learned?.let { (it * GAP_STEP_FACTOR).coerceIn(MIN_GAP_STEP_LENGTH_M, MAX_STEP_LENGTH_M) }
            ?: MAX_STEP_LENGTH_M

    /**
     * Where the walk being claimed begins: past a drive at the very start, if
     * there was one.
     *
     * Someone who presses Start and then drives to the park didn't walk the
     * drive, and the loop they walk at the park doesn't come back to their front
     * door. So the start moves to where the walking began. Only a real journey
     * moves it — the leading doubtful run has to end more than [minDriveMeters]
     * from where it began — because a wobble in the first fixes is no drive, and
     * moving the start a few dozen metres would put it somewhere the walker
     * never stood.
     *
     * While nothing has been walked yet, the walk begins where the doubt ends.
     */
    fun walkStart(stretches: List<Stretch>, path: List<LatLng>, minDriveMeters: Double): Int {
        if (stretches.isEmpty() || stretches.first().label != StretchLabel.DOUBTFUL) return 0
        val firstCounted = stretches.indexOfFirst { it.label != StretchLabel.DOUBTFUL }
        val driveEnd = if (firstCounted >= 0) stretches[firstCounted].fromIndex else stretches.last().toIndex
        return if (Geo.distanceMeters(path[0], path[driveEnd]) > minDriveMeters) driveEnd else 0
    }

    /** Consecutive doubtful stretches from [fromIndex] on, merged into runs of the path. */
    fun doubtfulRuns(stretches: List<Stretch>, fromIndex: Int = 0): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        for (s in stretches) {
            if (s.label != StretchLabel.DOUBTFUL || s.toIndex <= fromIndex) continue
            val from = maxOf(s.fromIndex, fromIndex)
            val previous = runs.lastOrNull()
            if (previous != null && previous.last == from) {
                runs[runs.lastIndex] = previous.first..s.toIndex
            } else {
                runs += from..s.toIndex
            }
        }
        return runs
    }
}
