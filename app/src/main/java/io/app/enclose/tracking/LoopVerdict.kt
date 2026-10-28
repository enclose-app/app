package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.PathSpikes

/** Why a stopped walk was kept without a claim. Stored by name beside the old void reasons. */
enum class UnclaimedReason {
    /** Stopped away from the start with no earlier loop to claim, or saved by choice. */
    NOT_CLOSED,

    /** The loop closed, but more of its boundary was bridged than [LoopVerdict.MAX_BRIDGED_SHARE] allows. */
    TOO_MUCH_BRIDGED,
}

/**
 * What Stop does with a walk: the one place a walk is judged, once, over
 * everything it recorded.
 *
 * Five steps, in this order:
 *
 *  1. **Drop a leading drive.** A walk that began in a car starts where the
 *     walking began — [StretchJudge.walkStart], which [TrackingManager] also
 *     applies live so "back to start" points at the right place.
 *  2. **Find the loop.** At the start, it ends here. Anywhere else it ends at the
 *     last return to the start ([LoopClosure]) — someone who forgot to press Stop
 *     and drove home still closed a loop.
 *  3. **Ask the watch** for any steps it is still holding, which the caller does
 *     before asking for a verdict: it is the one step that has to wait.
 *  4. **Bridge.** Each run of doubtful stretches becomes one straight line
 *     between its ends, so a detour by car or tram adds no area.
 *  5. **Apply the 80% rule.** If bridges make up [MAX_BRIDGED_SHARE] of the
 *     boundary or less, the loop can be claimed. Otherwise the walk is kept
 *     without a claim, with the split that decided it.
 *
 * What the bridge measures is the point of measuring it: a winding tram ride
 * that ends near where it began makes a short bridge and costs almost nothing,
 * while a straight drive across town makes a long one.
 *
 * Nothing here throws a walk away. The worst outcome is [Unclaimed], which is a
 * record with its route, time and steps.
 *
 * Pure, so the whole verdict is tested on plain points.
 */
object LoopVerdict {

    /** About 1.5 km of a 10k-step loop. At 10% one tram stop sinks a short loop; at 30% driving starts to pay. */
    const val MAX_BRIDGED_SHARE = 0.20

    /** One straight line standing in for a run of doubtful stretches. */
    data class Bridge(
        val fromIndex: Int,
        val toIndex: Int,
        val fromEpochMs: Long?,
        val toEpochMs: Long?,
        val meters: Double,
    )

    /** How the ground in a verdict was covered: what the claim dialog shows. */
    data class Split(
        val onFootMeters: Double,
        val bridges: List<Bridge>,
        /** Steps over the span judged, per source; null where it counted none. */
        val phoneSteps: Int?,
        val watchSteps: Int?,
        /** Part of the boundary crosses a signal gap no steps vouched for. */
        val unobservedGap: Boolean,
        /** A signal gap the steps covered, so it counts as walked. */
        val stepsCoveredGap: Boolean,
    ) {
        val bridgedMeters: Double get() = bridges.sumOf { it.meters }
    }

    sealed interface Result {
        val split: Split
    }

    /** The loop can be claimed. [ring] is the boundary as claimed, bridges included. */
    data class Claim(
        val ring: List<LatLng>,
        val startIndex: Int,
        val endIndex: Int,
        /** How far the closing point landed from the start. */
        val closingGapMeters: Double,
        /** [Split.bridgedMeters] as a share of the ring's perimeter. */
        val bridgedShare: Double,
        override val split: Split,
    ) : Result

    /** Kept, not claimed — and why. */
    data class Unclaimed(
        val reason: UnclaimedReason,
        /** Of the loop that closed, for [UnclaimedReason.TOO_MUCH_BRIDGED]; zero otherwise. */
        val bridgedShare: Double,
        override val split: Split,
    ) : Result

    /**
     * Judge [path] at Stop.
     *
     * @param judged false for a walk with no GPS behind it — map taps, a GPX
     *   replay — whose points carry no clock and are never judged.
     * @param atStart the walker is within the closing radius now, so the loop
     *   ends here rather than at an earlier return.
     */
    fun of(
        path: List<LatLng>,
        track: List<TrackPoint>,
        steps: List<StepInterval>,
        activityType: ActivityType,
        judged: Boolean,
        atStart: Boolean,
        closeRadiusMeters: Double,
        leaveRadiusMeters: Double,
        minPerimeterMeters: Double,
    ): Result {
        val log = StepLog(steps)
        val stretches = if (judged) {
            StretchJudge.stretches(path, track, log, activityType, complete = true)
        } else {
            emptyList()
        }
        val start = StretchJudge.walkStart(stretches, path, minDriveMeters = leaveRadiusMeters)

        // Nothing was walked at all: there is no loop to look for, and the
        // honest reason is not that it failed to close.
        if (stretches.isNotEmpty() && stretches.all { it.label == StretchLabel.DOUBTFUL }) {
            return Unclaimed(
                reason = UnclaimedReason.TOO_MUCH_BRIDGED,
                bridgedShare = 1.0,
                split = split(path, track, log, stretches, 0, path.lastIndex),
            )
        }

        val end = loopEnd(path, start, atStart, closeRadiusMeters, leaveRadiusMeters, minPerimeterMeters)
        if (end == null || end - start < 2) {
            return Unclaimed(
                reason = UnclaimedReason.NOT_CLOSED,
                bridgedShare = 0.0,
                split = split(path, track, log, stretches, start, path.lastIndex),
            )
        }

        val split = split(path, track, log, stretches, start, end)
        // The claimed outline: the walked points, with each bridged run reduced to
        // its two ends.
        val inside = BooleanArray(path.size)
        for (b in split.bridges) for (k in b.fromIndex + 1 until b.toIndex) inside[k] = true
        val walked = (start..end).filterNot { inside[it] }.map { path[it] }
        // A spike the live hold let through — two bad fixes in a row, or one
        // already on disk in a restored walk — would otherwise be claimed.
        val claimed = PathSpikes.remove(walked)
        val anchor = claimed.first()
        // Snap the loop shut *at the start* rather than at the (slightly off)
        // closing fix, so the claimed shape and the preview line close on it.
        val ring = if (claimed.size >= 2) claimed.dropLast(1) + anchor else claimed
        val perimeter = Geo.pathLengthMeters(ring)
        val share = if (perimeter > 0.0) split.bridgedMeters / perimeter else 0.0

        return if (share > MAX_BRIDGED_SHARE) {
            Unclaimed(UnclaimedReason.TOO_MUCH_BRIDGED, bridgedShare = share, split = split)
        } else {
            Claim(
                ring = ring,
                startIndex = start,
                endIndex = end,
                closingGapMeters = Geo.distanceMeters(claimed.last(), anchor),
                bridgedShare = share,
                split = split,
            )
        }
    }

    /**
     * The walk kept as a record without a claim — Save in the Stop dialog — with
     * the split over everything walked since its start.
     */
    fun saved(
        path: List<LatLng>,
        track: List<TrackPoint>,
        steps: List<StepInterval>,
        activityType: ActivityType,
        judged: Boolean,
        leaveRadiusMeters: Double,
    ): Unclaimed {
        val log = StepLog(steps)
        val stretches = if (judged) {
            StretchJudge.stretches(path, track, log, activityType, complete = true)
        } else {
            emptyList()
        }
        val start = StretchJudge.walkStart(stretches, path, minDriveMeters = leaveRadiusMeters)
        return Unclaimed(
            reason = UnclaimedReason.NOT_CLOSED,
            bridgedShare = 0.0,
            split = split(path, track, log, stretches, start, path.lastIndex),
        )
    }

    /**
     * The index the loop ends on, or null when it never closed: the last point
     * when the walker is at the start, otherwise the last return to it.
     *
     * Public because the Stop dialog asks it before anything is decided — "you
     * closed a loop at 14:32" is this point's time.
     */
    fun loopEnd(
        path: List<LatLng>,
        startIndex: Int,
        atStart: Boolean,
        closeRadiusMeters: Double,
        leaveRadiusMeters: Double,
        minPerimeterMeters: Double,
    ): Int? {
        if (startIndex !in path.indices) return null
        if (atStart) return path.lastIndex.takeIf { it - startIndex >= 2 }
        val from = path.subList(startIndex, path.size)
        return LoopClosure.endIndex(from, closeRadiusMeters, leaveRadiusMeters, minPerimeterMeters)
            ?.plus(startIndex)
    }

    /** The on-foot/bridged split of [start]..[end], with steps over the same span. */
    private fun split(
        path: List<LatLng>,
        track: List<TrackPoint>,
        log: StepLog,
        stretches: List<Stretch>,
        start: Int,
        end: Int,
    ): Split {
        val time = { i: Int -> track.getOrNull(i)?.atEpochMs }
        val bridges = StretchJudge.doubtfulRuns(stretches, fromIndex = start)
            .filter { it.first < end }
            .map { run -> run.first..minOf(run.last, end) }
            .filter { it.last > it.first }
            .map { run ->
                Bridge(
                    fromIndex = run.first,
                    toIndex = run.last,
                    fromEpochMs = time(run.first),
                    toEpochMs = time(run.last),
                    meters = Geo.distanceMeters(path[run.first], path[run.last]),
                )
            }
        val span = if (end > start) path.subList(start, end + 1) else emptyList()
        val bridgedGround = bridges.sumOf { Geo.pathLengthMeters(path.subList(it.fromIndex, it.toIndex + 1)) }
        val inSpan = stretches.filter { it.fromIndex >= start && it.toIndex <= end }
        val from = time(start)
        val to = time(end)
        return Split(
            onFootMeters = (Geo.pathLengthMeters(span) - bridgedGround).coerceAtLeast(0.0),
            bridges = bridges,
            phoneSteps = if (from != null && to != null) log.counted(StepSource.PHONE, from, to) else null,
            watchSteps = if (from != null && to != null) log.counted(StepSource.WATCH, from, to) else null,
            unobservedGap = inSpan.any { it.label == StretchLabel.UNOBSERVED },
            stepsCoveredGap = inSpan.any { it.gap && it.label == StretchLabel.ON_FOOT },
        )
    }
}
