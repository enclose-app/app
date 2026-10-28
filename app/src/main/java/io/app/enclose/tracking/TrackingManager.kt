package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Why nothing is reaching [TrackingManager] — the recorder could not be started,
 * or was started and then refused.
 *
 * This exists because starting a walk used to be optimistic in a way nothing
 * ever checked: the UI set the walk running, the service tried to subscribe to
 * location, and if that failed the service quietly stopped itself. The walk went
 * on showing "Walking" over a path that could never grow, and the only way out
 * was to stop and discard. A failure the app knows about has to be a failure the
 * app says out loud.
 */
enum class RecordingFailure {
    /** Location permission is missing or was revoked out from under the service. */
    PERMISSION,

    /**
     * The location request itself was refused — no provider, no Play Services,
     * or the platform declined for a reason of its own.
     */
    UNAVAILABLE,
}

/**
 * Owns the walk in progress. The [LocationService] feeds it GPS fixes and step
 * counts; the UI observes [walk].
 *
 * **Only the user ends a walk.** Every kept fix is recorded with its time, and
 * movement that doesn't look human-powered is labelled, live, by [StretchJudge]
 * — it is never a reason to stop recording or to end anything. The walk is
 * judged once, when Stop is pressed ([finishWalk]), by [LoopVerdict]: a loop
 * whose boundary is mostly walked becomes a [PendingClaim] for the UI to name
 * and confirm, and anything else is handed over on [unclaimedWalks] as a record.
 *
 * Persistence (SQLite) is handled by whoever listens, keeping this object free
 * of Android/DB dependencies.
 */
object TrackingManager {

    /** Snapshot of the walk in progress (or the idle state between walks). */
    data class WalkState(
        val isTracking: Boolean = false,
        /** Every point recorded, drive and all, in walked order. */
        val path: List<LatLng> = emptyList(),
        /**
         * What is known about each point of [path] besides where it is — see
         * [TrackPoint]. Index for index with [path]; a state built by hand may
         * leave it short, and a missing entry reads as a point with no clock.
         */
        val track: List<TrackPoint> = emptyList(),
        /** Step counts reported so far, from every source, in arrival order. */
        val steps: List<StepInterval> = emptyList(),
        /** Where the loop has to come back to: [path] at [startIndex]. */
        val start: LatLng? = null,
        /**
         * Index in [path] where the walk being claimed begins. Above zero once a
         * walk that began in a vehicle is seen to carry on on foot — the drive to
         * the park is no part of the loop — see [StretchJudge.walkStart].
         */
        val startIndex: Int = 0,
        val current: LatLng? = null,
        /** Everything recorded, drive included. */
        val distanceMeters: Double = 0.0,
        /**
         * Ground covered under the walker's own power since [start]: what was
         * recorded, less every stretch judged doubtful. What the panel shows,
         * and what the minimum loop is measured in.
         */
        val onFootMeters: Double = 0.0,
        /** The straight lines the claim would draw over doubtful stretches so far. */
        val bridgedMeters: Double = 0.0,
        /** Runs of [path] judged doubtful, merged; drawn dotted on the map. */
        val doubtfulRuns: List<IntRange> = emptyList(),
        /**
         * Why the latest judged stretch is doubtful, or null when it isn't. The
         * walk carries on either way; this only drives the notice that says so.
         */
        val doubtReason: DoubtReason? = null,
        /**
         * When the loop Stop would claim closed, while a stretch is in doubt away
         * from the start: "looks like a vehicle — your loop closed at 14:32", so
         * nobody rides on believing the walk is lost. Null at any other time, and
         * for a loop closed with no clock behind it.
         */
        val earlierLoopClosedAtMs: Long? = null,
        val distanceToStartMeters: Double? = null,
        /** True once the walk has moved beyond the start zone. */
        val hasLeftStart: Boolean = false,
        /** True once the walk is long enough on foot AND has left the start zone. */
        val canCloseLoop: Boolean = false,
        /**
         * True when stopping right now would close the loop here: long enough,
         * left the start zone, and currently within the closing radius of start.
         */
        val readyToClose: Boolean = false,
        /** Wall-clock ms of the first GPS fix in this walk; null until it lands. */
        val startedAtMs: Long? = null,
        /** Accuracy (meters) of the most recent fix; null when unknown. */
        val accuracyMeters: Float? = null,
        /** What the user set out to do; sets the speed ceiling and the wording. */
        val activityType: ActivityType = ActivityType.WALK,
        /** Confirmed climb so far, in metres. Noise-gated — see [ElevationAccumulator]. */
        val elevationGainMeters: Double = 0.0,
        /** Time actually spent moving, excluding stops — see [PauseTracker]. */
        val movingMs: Long = 0L,
        /**
         * True once this walk has been through at least one stretch with no
         * fixes at all — a dozing device, a tunnel, the screen off for a while.
         * The path bridges that stretch with a straight line, so the route is an
         * under-record of where the user actually went. Not fatal, and not the
         * user's doing: it is surfaced, not punished.
         */
        val hadSignalGap: Boolean = false,
        /**
         * Set when the walk is on screen but nothing is reaching it: the location
         * recorder could not be started, or stopped being able to run. Cleared by
         * the next fix that arrives, because a fix is the only proof it works.
         *
         * A walk that has already recorded ground keeps running with this set —
         * the points are real and Stop can still claim them. Only a walk with
         * nothing recorded is dropped back to idle, since there is nothing there
         * to protect.
         */
        val recordingFailure: RecordingFailure? = null,
        /** Steps the phone has counted this walk; null until it reports any. */
        val phoneSteps: Int? = null,
        /** Steps the watch has counted this walk; null until it reports any. */
        val watchSteps: Int? = null,
    ) {
        /** True while the latest stretch reads as a vehicle or as too fast. Still recording. */
        val doubtful: Boolean get() = doubtReason != null

        /**
         * [bridgedMeters] as a share of the loop so far — the figure the claim's
         * [LoopVerdict.MAX_BRIDGED_SHARE] will be measured against.
         */
        val bridgedShare: Double
            get() = (onFootMeters + bridgedMeters).let { if (it > 0.0) bridgedMeters / it else 0.0 }
    }

    /** A closed loop awaiting the user's decision to claim (with name/color). */
    data class PendingClaim(
        /** Stable id assigned at close; reused as the walk/territory id. */
        val id: String,
        /** The boundary as claimed: walked ground, with each doubtful run bridged. */
        val ring: List<LatLng>,
        val areaSqMeters: Double,
        val perimeterMeters: Double,
        /** How far the closing point landed from the start (the closing gap). */
        val distanceToStartMeters: Double,
        val closedAtEpochMs: Long,
        /** When the loop's walking began, so the walk's duration and pace survive. */
        val startedAtEpochMs: Long?,
        val elevationGainMeters: Double,
        /** Time spent moving, excluding stops, so pace reflects the walking. */
        val movingMs: Long,
        /**
         * True when part of the ring is a straight line across a signal gap that
         * no steps vouched for. The claim is still offered — the walking was
         * real — but the user is told, rather than the gap hidden.
         */
        val hadSignalGap: Boolean,
        val suggestedName: String,
        /** The loop's boundary walked under the walker's own power. */
        val onFootMeters: Double = perimeterMeters,
        /** The straight lines standing in for doubtful stretches, oldest first. */
        val bridges: List<LoopVerdict.Bridge> = emptyList(),
        /** Bridged metres as a share of [perimeterMeters]. */
        val bridgedShare: Double = 0.0,
        /** Steps counted over the loop, per source; null where none were. */
        val phoneSteps: Int? = null,
        val watchSteps: Int? = null,
        /** A signal gap the steps covered, so it counts as walked. */
        val stepsCoveredGap: Boolean = false,
        /**
         * Everything recorded, when it differs from [ring] — a leading drive, a
         * bridge, or ground covered after the loop closed. Kept on the walk row:
         * what isn't claimed is still the walker's.
         */
        val recordedTrack: List<LatLng>? = null,
    ) {
        val bridgedMeters: Double get() = bridges.sumOf { it.meters }
    }

    /**
     * A walk stopped without a claim, handed over before the walk is reset: Save
     * in the Stop dialog, or a loop with too much of it bridged. Whoever owns
     * persistence keeps it (EncloseApp); this object still has no DB of its own.
     */
    data class UnclaimedRecording(
        val id: String,
        val path: List<LatLng>,
        val startedAtEpochMs: Long?,
        val endedAtEpochMs: Long,
        val distanceMeters: Double,
        val reason: UnclaimedReason,
        val activityType: ActivityType,
        val onFootMeters: Double,
        val bridgedMeters: Double,
        /** Of the loop that closed, for [UnclaimedReason.TOO_MUCH_BRIDGED]; zero otherwise. */
        val bridgedShare: Double,
        val phoneSteps: Int?,
        val watchSteps: Int?,
    )

    private val _walk = MutableStateFlow(WalkState())
    val walk: StateFlow<WalkState> = _walk.asStateFlow()

    private val _pendingClaim = MutableStateFlow<PendingClaim?>(null)
    val pendingClaim: StateFlow<PendingClaim?> = _pendingClaim.asStateFlow()

    /**
     * Every walk stopped without a claim, with everything it recorded. The UI
     * explains it; EncloseApp keeps it under "Walks that didn't count".
     */
    private val _unclaimedWalks = MutableSharedFlow<UnclaimedRecording>(extraBufferCapacity = 4)
    val unclaimedWalks: SharedFlow<UnclaimedRecording> = _unclaimedWalks.asSharedFlow()

    /**
     * Emitted when the recorder could not be started, or stopped being able to
     * run. Nothing was walked and nothing was thrown away — the app simply
     * cannot do the thing it just said it was doing, and has to say so.
     */
    private val _recordingFailures = MutableSharedFlow<RecordingFailure>(extraBufferCapacity = 4)
    val recordingFailures: SharedFlow<RecordingFailure> = _recordingFailures.asSharedFlow()

    /** When true, use relaxed thresholds so a tap-tested loop can still close. */
    private var relaxed = false

    /** Running climb; its state belongs to a single walk. */
    private val elevation = ElevationAccumulator()

    /** Moving time, so pace isn't diluted by waiting at crossings. */
    private val pause = PauseTracker()

    /**
     * The previous fix, accepted or not. Segment speed is measured against this
     * rather than the last recorded point, so one dropped fix can't make the
     * following (legitimate) one look like a teleport.
     */
    private var lastFix: LatLng? = null
    private var lastFixAtElapsedMs: Long? = null

    /** Fixes too far from the path to append yet, oldest first; see [FixPlausibility]. */
    private data class HeldFix(
        val point: LatLng,
        val atElapsedMs: Long,
        val accuracyMeters: Float?,
        val info: TrackPoint,
    )
    private val heldFixes = mutableListOf<HeldFix>()

    /**
     * When the path's last point was last confirmed by a fix, and how accurate
     * that fix was — the baseline a held fix is judged against. Null when there is
     * no observed baseline (a new or restored walk), and then nothing is held.
     */
    private var trustedAtElapsedMs: Long? = null
    private var trustedAccuracyMeters: Float? = null

    /**
     * The signal went silent (or the process died) since the last point was
     * recorded, so the next one appended is reached across unobserved ground.
     */
    private var gapPending = false

    /** Wall clock minus the monotonic one, for fixes that arrive with no epoch time. */
    private var epochBaseMs: Long? = null

    /** Where the stretch still being walked begins: the end of the last one judged. */
    private var judgedUntil = 0

    /** Called from the UI when the user taps "Start walk" (or the first test tap). */
    fun startWalk(
        relaxedThresholds: Boolean = false,
        activityType: ActivityType = ActivityType.WALK,
    ) {
        relaxed = relaxedThresholds
        elevation.reset()
        pause.reset()
        resetPerWalk()
        _walk.value = WalkState(isTracking = true, activityType = activityType)
    }

    /**
     * Resume a walk that outlived the process that was recording it.
     *
     * The path, what is known about each point, the step counts, the start time
     * and the declared activity are handed back; distance, whether the start zone
     * was left, whether the loop may close and how each stretch counts are all
     * recomputed from them, so restored state can't disagree with the points it
     * came from — and a restored walk gets the verdict the same walk would have
     * got uninterrupted.
     *
     * [WalkState.readyToClose] deliberately starts false: closing means being
     * within the closing radius *now*, and the last recorded point is only
     * evidence of where the walker was before the process died. The next fix
     * settles it, and the line to it is a gap: nobody watched the ground between.
     *
     * Returns false (changing nothing) when there's no usable path to resume.
     */
    fun restore(
        path: List<LatLng>,
        startedAtMs: Long,
        activityType: ActivityType,
        /** Climb accumulated before the process died; altitude isn't stored per point. */
        elevationGainMeters: Double = 0.0,
        /** Moving time accumulated before the process died. */
        movingMs: Long = 0L,
        /** What was known about each point; shorter (or empty) for points stored without it. */
        track: List<TrackPoint> = emptyList(),
        steps: List<StepInterval> = emptyList(),
    ): Boolean {
        if (path.isEmpty()) return false

        relaxed = false
        // Resume the running totals, but not the references they were measured
        // against: the first fix after a restore would otherwise read as a jump
        // from wherever the walker was when the process died, and the interval
        // since then is time nobody observed.
        elevation.reset(elevationGainMeters)
        pause.reset(movingMs)
        resetPerWalk()

        val start = path.first()
        val last = path.last()
        val distance = Geo.pathLengthMeters(path)
        val leftStart = path.any { Geo.distanceMeters(start, it) > leaveStartRadiusMeters }
        val log = StepLog(steps)

        var state = WalkState(
            isTracking = true,
            path = path,
            track = List(path.size) { i -> track.getOrNull(i) ?: TrackPoint(atEpochMs = null) },
            steps = steps,
            start = start,
            current = last,
            distanceMeters = distance,
            onFootMeters = distance,
            distanceToStartMeters = Geo.distanceMeters(start, last),
            hasLeftStart = leftStart,
            canCloseLoop = leftStart && distance >= minPerimeterMeters,
            startedAtMs = startedAtMs,
            activityType = activityType,
            elevationGainMeters = elevationGainMeters,
            movingMs = movingMs,
            // A restore only ever happens because recording was interrupted, and
            // nothing on disk says for how long. Reporting the gap when it was
            // brief costs a line of explanation; staying quiet when it was long
            // hides a straight line across ground nobody recorded.
            hadSignalGap = true,
            phoneSteps = log.total(StepSource.PHONE),
            watchSteps = log.total(StepSource.WATCH),
        )
        if (path.size >= 2) state = judged(state)
        gapPending = true
        _walk.value = state.copy(readyToClose = false)
        return true
    }

    /** Abandon the current walk without keeping anything — only behind the confirmed Discard. */
    fun cancelWalk() {
        _walk.value = WalkState(isTracking = false)
    }

    /**
     * Told by whoever owns the location stream that it cannot run: permission is
     * gone, or the platform refused the request.
     *
     * Nothing downstream of [startWalk] used to check that fixes were actually
     * arriving, so this failure was invisible — the panel said "Walking" over a
     * path that could never grow. What happens next depends on whether anything
     * has been recorded yet, and the asymmetry is the same one that governs the
     * rest of this app:
     *
     *  - **Nothing recorded.** Drop straight back to idle. There is nothing to
     *    lose, and leaving a walk running that cannot record is the state the
     *    user could only escape by stopping and discarding.
     *  - **Ground already recorded.** Keep the walk exactly where it is. Those
     *    points are somewhere the user actually went, and Stop can still claim
     *    them — a walk is never thrown away to report a problem with it.
     *
     * Either way the reason goes out on [recordingFailures] so it can be
     * explained rather than guessed at.
     */
    fun reportRecordingUnavailable(failure: RecordingFailure) {
        val state = _walk.value
        if (!state.isTracking) return
        _walk.value = if (state.path.isEmpty()) {
            WalkState(isTracking = false)
        } else {
            state.copy(recordingFailure = failure)
        }
        _recordingFailures.tryEmit(failure)
    }

    /**
     * Feed a new GPS fix. This only records and labels — the walk is never
     * stopped or closed from here; that happens when the user presses Stop (see
     * [finishWalk]). [WalkState.readyToClose] reflects whether stopping now would
     * close the loop where the walker stands.
     */
    fun onLocation(
        point: LatLng,
        accuracyMeters: Float? = null,
        /** The fix's own speed, when the provider reports one. */
        speedMps: Float? = null,
        /** Monotonic time of the fix. Null skips the timing checks (test taps). */
        atElapsedMs: Long? = null,
        /** Latest activity classification, when available. */
        motion: MotionSample? = null,
        /** Altitude in metres, when the provider reports one. */
        altitudeMeters: Double? = null,
        /**
         * Wall-clock time of the fix, epoch ms — what the point is stored and
         * judged by, and what step counts are matched against. Derived from
         * [atElapsedMs] when absent.
         */
        atEpochMs: Long? = null,
    ) {
        var state = _walk.value
        if (!state.isTracking) return

        // A fix arriving is the only proof the recorder works, so it is what
        // clears a reported failure — not the notice being dismissed on screen.
        if (state.recordingFailure != null) state = state.copy(recordingFailure = null)

        // A fix this vague describes nothing, so it must shape nothing: not the
        // path, and not the judging of it either. Reacquiring after signal loss
        // routinely lands hundreds of metres out. Keep the marker roughly fresh
        // and wait for a fix worth believing.
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS) {
            val toStart = state.start?.let { Geo.distanceMeters(it, point) }
            _walk.value = state.copy(
                current = point,
                distanceToStartMeters = toStart ?: state.distanceToStartMeters,
                accuracyMeters = accuracyMeters,
                readyToClose = state.canCloseLoop &&
                    toStart != null &&
                    toStart <= closureRadiusMeters,
            )
            return
        }

        val info = TrackPoint(
            atEpochMs = epochOf(atElapsedMs, atEpochMs),
            motion = MotionEvidence.of(motion, atElapsedMs),
        )

        // Set when this fix arrived impossibly fast from the previous one.
        var snapped = false

        // Test mode is exempt from timing: tapped points jump across the map by
        // design and carry no clock.
        if (atElapsedMs != null && !relaxed) {
            // Losing the signal shows up in two different shapes, and both are
            // gaps — ground nobody observed — rather than speed.
            //
            //  - Silence: a dozing device stops delivering entirely, then hands
            //    the missed stretch over in a burst on wake.
            //  - A frozen fix: the provider keeps reporting the last position it
            //    was sure of, at the normal interval, and then snaps to the true
            //    one when it reacquires. Nothing looks wrong until the snap, so
            //    the silence rule never sees it — this is the common one indoors
            //    and with the screen off.
            //
            // The snap is recognised by being physically impossible rather than
            // merely fast: no road vehicle sustains REACQUISITION_SPEED_MPS, so
            // a segment that quick is the map catching up, not the user moving.
            // Either way the line to the next point is judged whole, as one
            // stretch, on how long it took and on the steps taken in it.
            val silenceMs = lastFixAtElapsedMs?.let { atElapsedMs - it }
            val silent = silenceMs != null && silenceMs > SIGNAL_GAP_MS
            val segmentSpeed = segmentSpeedMps(point, atElapsedMs)
            if (silent || (segmentSpeed != null && segmentSpeed > REACQUISITION_SPEED_MPS)) {
                // Drops the baseline, so the jump itself never becomes a speed.
                lastFix = null
                lastFixAtElapsedMs = null
                // Silence is a gap whatever comes next. A snap is only one if the
                // fix it snapped to is kept — a spike that is dropped below
                // leaves no hole in the recording.
                if (silent) {
                    state = state.copy(hadSignalGap = true)
                    gapPending = true
                } else {
                    snapped = true
                }
            }

            val speed = fusedSpeedMps(point, speedMps, atElapsedMs)
            lastFix = point
            lastFixAtElapsedMs = atElapsedMs
            // Time on a stretch judged doubtful is not walking time.
            state = state.copy(movingMs = pause.update(atElapsedMs, speed, motion, credit = !state.doubtful))
        }

        // Climb is credited on any usable fix, including ones too close to the
        // previous point to extend the path: height can change without covering
        // ground — stairs, or a switchback tighter than MIN_MOVE_METERS.
        val climb = elevation.add(altitudeMeters)

        // First fix of the walk sets the anchor. Fixes too vague to trust have
        // already been sent back above, so this one is fit to anchor to.
        if (state.path.isEmpty()) {
            _walk.value = state.copy(
                path = listOf(point),
                track = listOf(info),
                start = point,
                startIndex = 0,
                current = point,
                distanceToStartMeters = 0.0,
                startedAtMs = info.atEpochMs ?: System.currentTimeMillis(),
                accuracyMeters = accuracyMeters,
                elevationGainMeters = climb,
            )
            gapPending = false
            trustFix(atElapsedMs, accuracyMeters)
            return
        }

        // A fix nobody could have reached from the path is held back until the
        // fixes after it say what it was — see [FixPlausibility]. Only timed
        // fixes are judged: taps and imports have no clock to judge them by.
        if (atElapsedMs != null && !relaxed) {
            val fix = HeldFix(point, atElapsedMs, accuracyMeters, info)
            val anchorAt = trustedAtElapsedMs
            val nearPath = anchorAt == null || !FixPlausibility.isJump(
                state.path.last(), anchorAt, trustedAccuracyMeters, point, atElapsedMs, accuracyMeters,
            )
            val lastHeld = heldFixes.lastOrNull()
            val nearHeld = lastHeld != null && !FixPlausibility.isJump(
                lastHeld.point, lastHeld.atElapsedMs, lastHeld.accuracyMeters,
                point, atElapsedMs, accuracyMeters,
            )
            when {
                // Nothing held, nothing odd: the ordinary case.
                lastHeld == null && nearPath -> Unit

                // Back where the path was: what was held was a spike. Drop it,
                // and this fix's jump *from* the spike is no gap in the recording.
                nearPath && !nearHeld -> {
                    heldFixes.clear()
                    snapped = false
                }

                // Agreeing with what is held. Agreement alone proves nothing —
                // providers repeat a bad fix, and a spike would confirm itself —
                // so it takes a while of it. A frozen fix snapping to the truth
                // agrees for the rest of the walk; that costs it only the wait.
                nearHeld -> {
                    heldFixes += fix
                    val held = heldFixes.toList()
                    val heldForMs = atElapsedMs - held.first().atElapsedMs
                    if (held.size < HOLD_MIN_FIXES || heldForMs < HOLD_CONFIRM_MS) {
                        _walk.value = state.copy(accuracyMeters = accuracyMeters, elevationGainMeters = climb)
                        return
                    }
                    // The position really did move. The held fixes go on the
                    // path first, and the straight line to the first of them
                    // crosses ground nobody observed.
                    heldFixes.clear()
                    state = state.copy(hadSignalGap = true)
                    held.dropLast(1).forEachIndexed { k, h ->
                        val jump = if (k == 0) h.info.copy(afterGap = true) else h.info
                        state = extend(state, h.point, h.accuracyMeters, state.elevationGainMeters, jump)
                    }
                    snapped = false
                }

                // A jump from everything so far: start holding afresh.
                else -> {
                    heldFixes.clear()
                    heldFixes += fix
                    _walk.value = state.copy(accuracyMeters = accuracyMeters, elevationGainMeters = climb)
                    return
                }
            }
        }

        if (snapped) state = state.copy(hadSignalGap = true)
        _walk.value = extend(state, point, accuracyMeters, climb, info.copy(afterGap = snapped))
        trustFix(atElapsedMs, accuracyMeters)
    }

    /**
     * Take a count of steps from the phone or the watch. Steps are evidence for
     * the stretches they span, so the walk is judged again with them — a count
     * arriving late (the watch batches) can still vouch for ground already
     * recorded. A report overlapping one the same source already made is
     * dropped rather than counting the same steps twice.
     */
    fun onSteps(interval: StepInterval) = onSteps(listOf(interval))

    /**
     * Several reports at once — the watch sends every interval it has each
     * time, most of them already here — judged once rather than per report.
     */
    fun onSteps(intervals: List<StepInterval>) {
        val state = _walk.value
        if (!state.isTracking) return
        // The watch resends what it sent before; a repeat is found without a scan.
        val seen = state.steps.mapTo(HashSet()) { Triple(it.source, it.fromEpochMs, it.toEpochMs) }
        val taken = mutableListOf<StepInterval>()
        for (interval in intervals) {
            if (interval.toEpochMs <= interval.fromEpochMs || interval.steps < 0) continue
            if (!seen.add(Triple(interval.source, interval.fromEpochMs, interval.toEpochMs))) continue
            fun overlaps(it: StepInterval) = it.source == interval.source &&
                it.fromEpochMs < interval.toEpochMs &&
                interval.fromEpochMs < it.toEpochMs
            if (state.steps.none(::overlaps) && taken.none(::overlaps)) taken += interval
        }
        if (taken.isEmpty()) return

        fun added(source: StepSource, before: Int?): Int? {
            val counted = taken.filter { it.source == source }
            return if (counted.isEmpty()) before else (before ?: 0) + counted.sumOf { it.steps }
        }
        var next = state.copy(
            steps = state.steps + taken,
            phoneSteps = added(StepSource.PHONE, state.phoneSteps),
            watchSteps = added(StepSource.WATCH, state.watchSteps),
        )
        if (!relaxed && next.path.size >= 2) next = judged(next)
        _walk.value = next
    }

    /**
     * Whether Stop would bridge anything as the walk stands — the one case in
     * which waiting for the watch's last steps can change the verdict, since
     * its steps can only turn a doubtful stretch into one on foot.
     */
    fun hasDoubt(state: WalkState = _walk.value): Boolean {
        if (!state.isTracking || relaxed || state.path.size < 2) return false
        return StretchJudge.stretches(
            state.path, state.track, StepLog(state.steps), state.activityType, complete = true,
        ).any { it.label == StretchLabel.DOUBTFUL }
    }

    /**
     * [state] with [point] added to the path, or — inside [MIN_MOVE_METERS] of the
     * last point — with only the live figures moved on.
     */
    private fun extend(
        state: WalkState,
        point: LatLng,
        accuracyMeters: Float?,
        climb: Double,
        info: TrackPoint,
    ): WalkState {
        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter so the path stays clean.
        if (Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            // Within a few metres of where the signal went: no ground was
            // crossed unseen, so there is no gap to judge.
            gapPending = false
            return state.copy(
                current = point,
                distanceToStartMeters = toStart,
                accuracyMeters = accuracyMeters,
                readyToClose = state.canCloseLoop && toStart <= closureRadiusMeters,
                elevationGainMeters = climb,
            )
        }

        val segment = Geo.distanceMeters(last, point)
        val track = if (state.track.size == state.path.size) {
            state.track
        } else {
            List(state.path.size) { i -> state.track.getOrNull(i) ?: TrackPoint(atEpochMs = null) }
        }
        val afterGap = info.afterGap || gapPending
        gapPending = false
        // Counted on foot until judged otherwise: the stretch still being walked
        // hasn't been judged yet, and most never will be anything else.
        val onFoot = state.onFootMeters + segment
        val leftStart = state.hasLeftStart || toStart > leaveStartRadiusMeters
        val canClose = leftStart && onFoot >= minPerimeterMeters

        val next = state.copy(
            path = state.path + point,
            track = track + info.copy(afterGap = afterGap),
            current = point,
            distanceMeters = state.distanceMeters + segment,
            onFootMeters = onFoot,
            distanceToStartMeters = toStart,
            hasLeftStart = leftStart,
            canCloseLoop = canClose,
            accuracyMeters = accuracyMeters,
            readyToClose = canClose && toStart <= closureRadiusMeters,
            elevationGainMeters = climb,
        )
        return if (!relaxed && stretchDue(next)) judged(next) else next
    }

    /** True once the stretch still being walked has run its full length, or a gap just ended. */
    private fun stretchDue(state: WalkState): Boolean {
        val last = state.track.lastOrNull() ?: return false
        if (last.afterGap) return true
        val from = state.track.getOrNull(judgedUntil)?.atEpochMs ?: return false
        val to = last.atEpochMs ?: return false
        return to - from >= StretchJudge.STRETCH_MS
    }

    /**
     * [state] with every stretch judged again — see [StretchJudge] — and every
     * figure measured from the start brought into line: the start itself moves
     * past a leading drive, so "back to start" points at where the walking began.
     */
    private fun judged(state: WalkState): WalkState {
        val path = state.path
        val stretches = StretchJudge.stretches(
            path, state.track, StepLog(state.steps), state.activityType, complete = false,
        )
        judgedUntil = stretches.lastOrNull()?.toIndex ?: 0
        val startIndex = StretchJudge.walkStart(stretches, path, minDriveMeters = leaveStartRadiusMeters)
        val runs = StretchJudge.doubtfulRuns(stretches, fromIndex = startIndex)
        val fromStart = Geo.pathLengthMeters(path.subList(startIndex, path.size))
        val doubtfulGround = runs.sumOf { Geo.pathLengthMeters(path.subList(it.first, it.last + 1)) }
        val onFoot = (fromStart - doubtfulGround).coerceAtLeast(0.0)

        val start = path[startIndex]
        val leftStart = if (startIndex == state.startIndex && state.start == start) {
            state.hasLeftStart
        } else {
            path.subList(startIndex, path.size).any { Geo.distanceMeters(start, it) > leaveStartRadiusMeters }
        }
        val toStart = state.current?.let { Geo.distanceMeters(start, it) }
        val canClose = leftStart && onFoot >= minPerimeterMeters
        val next = state.copy(
            start = start,
            startIndex = startIndex,
            onFootMeters = onFoot,
            bridgedMeters = runs.sumOf { Geo.distanceMeters(path[it.first], path[it.last]) },
            doubtfulRuns = runs,
            doubtReason = stretches.lastOrNull()?.reason,
            distanceToStartMeters = toStart,
            hasLeftStart = leftStart,
            canCloseLoop = canClose,
            readyToClose = canClose && toStart != null && toStart <= closureRadiusMeters,
        )
        // Looked for only while it would be said: a scan of the path per fix is
        // worth it then, and not for a walk going well.
        val earlier = if (next.doubtful && !next.readyToClose) earlierLoop(next)?.closedAtEpochMs else null
        return next.copy(earlierLoopClosedAtMs = earlier)
    }

    /** Record that the path's last point was still where the walker was at [atElapsedMs]. */
    private fun trustFix(atElapsedMs: Long?, accuracyMeters: Float?) {
        trustedAtElapsedMs = atElapsedMs
        trustedAccuracyMeters = accuracyMeters
    }

    /** Forget everything that belonged to the previous walk's fixes. */
    private fun resetPerWalk() {
        lastFix = null
        lastFixAtElapsedMs = null
        heldFixes.clear()
        trustedAtElapsedMs = null
        trustedAccuracyMeters = null
        gapPending = false
        epochBaseMs = null
        judgedUntil = 0
    }

    /** The wall-clock time of a fix: the one given, or the monotonic one carried onto the wall clock. */
    private fun epochOf(atElapsedMs: Long?, atEpochMs: Long?): Long? {
        if (atEpochMs != null) return atEpochMs
        if (atElapsedMs == null) return null
        val base = epochBaseMs ?: (System.currentTimeMillis() - atElapsedMs).also { epochBaseMs = it }
        return base + atElapsedMs
    }

    /**
     * Called when the user presses Stop, from the panel or the watch. Never
     * throws a walk away: at the start it closes the loop here; anywhere else it
     * claims the loop that last closed, if one did. Either way [LoopVerdict]
     * decides whether enough of it was walked, and a walk that can't be claimed
     * is handed over on [unclaimedWalks] instead.
     */
    fun finishWalk() {
        val state = _walk.value
        if (!state.isTracking) return
        when (val verdict = verdictFor(state, atStart = state.readyToClose)) {
            is LoopVerdict.Claim -> closeLoop(state, verdict)
            is LoopVerdict.Unclaimed -> keep(state, verdict)
        }
    }

    /**
     * Stop and keep the walk as it is, claiming nothing — Save in the Stop
     * dialog, which is what Stop away from the start leads with.
     */
    fun saveWalk() {
        val state = _walk.value
        if (!state.isTracking) return
        keep(
            state,
            LoopVerdict.saved(
                path = state.path,
                track = state.track,
                steps = state.steps,
                activityType = state.activityType,
                judged = !relaxed,
                leaveRadiusMeters = leaveStartRadiusMeters,
            ),
        )
    }

    /**
     * The loop this walk last closed, when the walker is away from the start —
     * what the Stop dialog offers to claim — or null when it never closed one.
     * [closedAtEpochMs] is null for a walk with no clock behind it.
     */
    data class EarlierLoop(val closedAtEpochMs: Long?)

    fun earlierLoop(state: WalkState = _walk.value): EarlierLoop? {
        if (!state.isTracking) return null
        val end = LoopVerdict.loopEnd(
            path = state.path,
            startIndex = state.startIndex,
            atStart = false,
            closeRadiusMeters = closureRadiusMeters,
            leaveRadiusMeters = leaveStartRadiusMeters,
            minPerimeterMeters = minPerimeterMeters,
        ) ?: return null
        return EarlierLoop(state.track.getOrNull(end)?.atEpochMs)
    }

    private fun verdictFor(state: WalkState, atStart: Boolean): LoopVerdict.Result = LoopVerdict.of(
        path = state.path,
        track = state.track,
        steps = state.steps,
        activityType = state.activityType,
        judged = !relaxed,
        atStart = atStart,
        closeRadiusMeters = closureRadiusMeters,
        leaveRadiusMeters = leaveStartRadiusMeters,
        minPerimeterMeters = minPerimeterMeters,
    )

    private fun closeLoop(state: WalkState, claim: LoopVerdict.Claim) {
        val ring = claim.ring
        val split = claim.split
        val trimmed = claim.startIndex > 0 ||
            claim.endIndex < state.path.lastIndex ||
            split.bridges.isNotEmpty()
        // Stop tracking but keep the closed ring on screen as a preview.
        _walk.value = WalkState(
            isTracking = false,
            path = ring,
            start = ring.first(),
            hadSignalGap = state.hadSignalGap,
        )
        _pendingClaim.value = PendingClaim(
            id = UUID.randomUUID().toString(),
            ring = ring,
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = Geo.pathLengthMeters(ring),
            distanceToStartMeters = claim.closingGapMeters,
            closedAtEpochMs = System.currentTimeMillis(),
            // A walk that began with a drive began, as far as the loop goes,
            // where the walking did.
            startedAtEpochMs = state.track.getOrNull(claim.startIndex)?.atEpochMs
                ?.takeIf { claim.startIndex > 0 }
                ?: state.startedAtMs,
            elevationGainMeters = state.elevationGainMeters,
            movingMs = state.movingMs,
            hadSignalGap = split.unobservedGap,
            suggestedName = NameGenerator.random(),
            onFootMeters = split.onFootMeters,
            bridges = split.bridges,
            bridgedShare = claim.bridgedShare,
            phoneSteps = split.phoneSteps,
            watchSteps = split.watchSteps,
            stepsCoveredGap = split.stepsCoveredGap,
            recordedTrack = state.path.takeIf { trimmed },
        )
    }

    /** End the walk without a claim, handing over everything it recorded first. */
    private fun keep(state: WalkState, verdict: LoopVerdict.Unclaimed) {
        // Taken before the reset below, which would otherwise erase it. A walk
        // with nothing on the ground has nothing to keep.
        if (state.path.size >= 2) {
            _unclaimedWalks.tryEmit(
                UnclaimedRecording(
                    id = UUID.randomUUID().toString(),
                    path = state.path,
                    startedAtEpochMs = state.startedAtMs,
                    endedAtEpochMs = System.currentTimeMillis(),
                    distanceMeters = state.distanceMeters,
                    reason = verdict.reason,
                    activityType = state.activityType,
                    onFootMeters = verdict.split.onFootMeters,
                    bridgedMeters = verdict.split.bridgedMeters,
                    bridgedShare = verdict.bridgedShare,
                    phoneSteps = verdict.split.phoneSteps,
                    watchSteps = verdict.split.watchSteps,
                ),
            )
        }
        resetPerWalk()
        _walk.value = WalkState(isTracking = false)
    }

    /**
     * Best estimate of how fast the user is moving right now: the larger of the
     * fix's own speed and the speed implied by the distance since the previous
     * fix. Only moving time uses it; stretches are judged on displacement.
     *
     * Must be called before [lastFix] is advanced to the new fix.
     */
    private fun fusedSpeedMps(point: LatLng, reportedMps: Float?, atElapsedMs: Long): Double? {
        val segment = segmentSpeedMps(point, atElapsedMs)
        val reported = reportedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        return listOfNotNull(segment, reported).maxOrNull()
    }

    /**
     * Speed implied by the ground covered since the previous fix, or null when
     * there is no baseline to measure against.
     *
     * Separate from [fusedSpeedMps] because the reacquisition check needs the
     * measured segment on its own: a provider that reports a plausible speed
     * while its *position* jumps would otherwise hide the jump.
     *
     * Must be called before [lastFix] is advanced to the new fix.
     */
    private fun segmentSpeedMps(point: LatLng, atElapsedMs: Long): Double? {
        val previous = lastFix ?: return null
        val previousAt = lastFixAtElapsedMs ?: return null
        if (atElapsedMs <= previousAt) return null
        return Geo.distanceMeters(previous, point) / ((atElapsedMs - previousAt) / 1000.0)
    }

    /** Clears the pending claim and resets the map to idle (claim or discard). */
    fun clearPending() {
        _pendingClaim.value = null
        _walk.value = WalkState()
    }

    // --- Effective thresholds (relaxed while tap-testing) ---------------------
    /** How close to the start counts as "closing the loop", for this walk. */
    val closureRadiusMeters: Double
        get() = if (relaxed) CLOSURE_RADIUS_TEST_METERS else CLOSURE_RADIUS_METERS

    /** How far the walk must leave the start before a close can count. */
    val leaveStartRadiusMeters: Double
        get() = if (relaxed) LEAVE_START_TEST_METERS else LEAVE_START_RADIUS_METERS

    /** Minimum walked distance before a loop may be claimed. */
    val minPerimeterMeters: Double
        get() = if (relaxed) MIN_PERIMETER_TEST_METERS else MIN_PERIMETER_METERS

    // --- Tuning ---------------------------------------------------------------
    // Real GPS walks: precise closing, meaningful loop size.
    const val CLOSURE_RADIUS_METERS = 10.0
    const val LEAVE_START_RADIUS_METERS = 80.0
    const val MIN_PERIMETER_METERS = 200.0
    // Test mode (map taps) and imported tracks: forgiving, so a loop is actually
    // reachable on screen, and a recorded track stopped a few dozen metres short
    // of its start still closes. Neither is judged for speed, so a wider circle
    // here gives away no anti-cheat protection.
    private const val CLOSURE_RADIUS_TEST_METERS = 75.0
    private const val LEAVE_START_TEST_METERS = 40.0
    private const val MIN_PERIMETER_TEST_METERS = 80.0
    /**
     * How long, and over how many fixes, a jump must keep agreeing with itself
     * before it goes on the path. One repeat is not enough: the provider hands
     * the same bad fix over several times, which on a device confirmed every
     * spike by itself.
     */
    private const val HOLD_CONFIRM_MS = 10_000L
    private const val HOLD_MIN_FIXES = 3
    /** Fixes closer than this to the previous point are treated as noise. */
    private const val MIN_MOVE_METERS = 4.0

    /**
     * Fixes worse than this accuracy (meters) are kept off the path.
     *
     * Public because the UI has to be able to say so. A fix this vague is
     * discarded outright, and "discarded" looks exactly like "no signal" from the
     * panel — which is how a walk under approximate-only location could run for
     * an hour recording nothing while the GPS chip read-out sat there quietly
     * showing a number.
     */
    const val MAX_ACCURACY_METERS = 50f

    /**
     * Silence longer than this means the fixes stopped coming, not that the
     * walker stopped moving. Fixes are requested every 3 s and tolerated down to
     * 1 s, so 45 s is roughly fifteen missed ones — comfortably past a couple of
     * dropped updates under trees, and short enough that a real doze window
     * (minutes) is always caught. Matched in spirit to
     * [PauseTracker.MAX_CREDITED_GAP_MS], which refuses to credit such a stretch
     * as either moving or paused for the same reason.
     */
    const val SIGNAL_GAP_MS = 45_000L

    /**
     * A single segment quicker than this is the position catching up, not the
     * user moving. 55 m/s ≈ 200 km/h: faster than any road vehicle in normal
     * use, so it cannot be the drive that [StretchJudge] exists to catch, while a
     * reacquisition snap after a frozen fix is typically an order of magnitude
     * beyond it (300 m against a 1 s interval is 300 m/s).
     *
     * Deliberately well clear of [StretchJudge.ABSOLUTE_MAX_SPEED_MPS]: everything
     * between the two is still judged as movement. Only the physically impossible
     * is reclassified as an artefact — and even then the ground it skips is a gap,
     * judged whole on how long it took, rather than quietly absorbed into the route.
     */
    const val REACQUISITION_SPEED_MPS = 55.0
}
