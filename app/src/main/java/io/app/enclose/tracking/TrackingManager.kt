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
 * Owns the walk in progress and runs loop-closure detection. The
 * [LocationService] feeds it GPS fixes; the UI observes [walk]. When a loop
 * closes it publishes a [PendingClaim] on [pendingClaim] — the UI shows a modal
 * to name/color it, then confirms or discards. Persistence (SQLite) is handled
 * by whoever confirms, keeping this object free of Android/DB dependencies.
 */
object TrackingManager {

    /** Snapshot of the walk in progress (or the idle state between walks). */
    data class WalkState(
        val isTracking: Boolean = false,
        val path: List<LatLng> = emptyList(),
        val start: LatLng? = null,
        val current: LatLng? = null,
        val distanceMeters: Double = 0.0,
        val distanceToStartMeters: Double? = null,
        /** True once the walk has moved beyond the start zone. */
        val hasLeftStart: Boolean = false,
        /** True once the walk is long enough AND has left the start zone. */
        val canCloseLoop: Boolean = false,
        /**
         * True when stopping right now would claim a valid loop: long enough,
         * left the start zone, and currently within the closing radius of start.
         */
        val readyToClose: Boolean = false,
        /** Wall-clock ms of the first GPS fix in this walk; null until it lands. */
        val startedAtMs: Long? = null,
        /** Accuracy (meters) of the most recent fix; null when unknown. */
        val accuracyMeters: Float? = null,
        /**
         * Set while movement is being rejected as not human-powered. Fixes are
         * not recorded and the loop cannot be closed while this is non-null.
         */
        val blockedReason: BlockReason? = null,
        /** Monotonic ms at which blocking began, for the warning's countdown. */
        val blockedSinceElapsedMs: Long? = null,
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
    ) {
        /** True while a vehicle (or implausible speed) is suspending recording. */
        val motionBlocked: Boolean get() = blockedReason != null
    }

    /** A closed loop awaiting the user's decision to claim (with name/color). */
    data class PendingClaim(
        /** Stable id assigned at close; reused as the walk/territory id. */
        val id: String,
        val ring: List<LatLng>,
        val areaSqMeters: Double,
        val perimeterMeters: Double,
        /** How far the closing point landed from the start (the closing gap). */
        val distanceToStartMeters: Double,
        val closedAtEpochMs: Long,
        /** When the first fix landed, so the walk's duration and pace survive. */
        val startedAtEpochMs: Long?,
        val elevationGainMeters: Double,
        /** Time spent moving, excluding stops, so pace reflects the walking. */
        val movingMs: Long,
        /**
         * True when the recording lost the signal at some point, so part of the
         * ring is a straight line across ground that was never observed. The
         * claim is still offered — the walking was real — but the user is told,
         * rather than the walk being thrown away or the gap hidden.
         */
        val hadSignalGap: Boolean,
        val suggestedName: String,
    )

    private val _walk = MutableStateFlow(WalkState())
    val walk: StateFlow<WalkState> = _walk.asStateFlow()

    private val _pendingClaim = MutableStateFlow<PendingClaim?>(null)
    val pendingClaim: StateFlow<PendingClaim?> = _pendingClaim.asStateFlow()

    /**
     * Emitted when a walk is thrown away because the movement wasn't
     * human-powered. The UI explains it; whoever owns the location service also
     * listens so it can be shut down.
     */
    private val _voidEvents = MutableSharedFlow<VoidReason>(extraBufferCapacity = 4)
    val voidEvents: SharedFlow<VoidReason> = _voidEvents.asSharedFlow()

    /** When true, use relaxed thresholds so a tap-tested loop can still close. */
    private var relaxed = false

    /** Rejects vehicle movement; state is per-walk, so it's reset on start. */
    private val motionGate = MotionGate()

    /** Running climb; like the gate, its state belongs to a single walk. */
    private val elevation = ElevationAccumulator()

    /** Moving time, so pace isn't diluted by waiting at crossings. */
    private val pause = PauseTracker()

    /**
     * The previous fix, accepted or not. Segment speed is measured against this
     * rather than the last recorded point, so one rejected fix can't make the
     * following (legitimate) one look like a teleport.
     */
    private var lastFix: LatLng? = null
    private var lastFixAtElapsedMs: Long? = null

    /** Called from the UI when the user taps "Start walk" (or the first test tap). */
    fun startWalk(
        relaxedThresholds: Boolean = false,
        activityType: ActivityType = ActivityType.WALK,
    ) {
        relaxed = relaxedThresholds
        motionGate.reset(activityType)
        elevation.reset()
        pause.reset()
        lastFix = null
        lastFixAtElapsedMs = null
        _walk.value = WalkState(isTracking = true, activityType = activityType)
    }

    /**
     * Resume a walk that outlived the process that was recording it.
     *
     * Only the path, its start time, and the declared activity are handed back;
     * distance, whether the start zone was left and whether the loop may close
     * are all recomputed from the path, so restored state can't disagree with
     * the points it came from.
     *
     * [WalkState.readyToClose] deliberately starts false: closing means being
     * within the closing radius *now*, and the last recorded point is only
     * evidence of where the walker was before the process died. The next fix
     * settles it. The motion gate starts clean for the same reason — its speed
     * window describes movement nobody is still observing.
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
    ): Boolean {
        if (path.isEmpty()) return false

        relaxed = false
        motionGate.reset(activityType)
        // Resume the running totals, but not the references they were measured
        // against: the first fix after a restore would otherwise read as a jump
        // from wherever the walker was when the process died, and the interval
        // since then is time nobody observed.
        elevation.reset(elevationGainMeters)
        pause.reset(movingMs)
        lastFix = null
        lastFixAtElapsedMs = null

        val start = path.first()
        val last = path.last()
        val distance = Geo.pathLengthMeters(path)
        val leftStart = path.any { Geo.distanceMeters(start, it) > leaveStartRadiusMeters }

        _walk.value = WalkState(
            isTracking = true,
            path = path,
            start = start,
            current = last,
            distanceMeters = distance,
            distanceToStartMeters = Geo.distanceMeters(start, last),
            hasLeftStart = leftStart,
            canCloseLoop = leftStart && distance >= minPerimeterMeters,
            readyToClose = false,
            startedAtMs = startedAtMs,
            activityType = activityType,
            elevationGainMeters = elevationGainMeters,
            movingMs = movingMs,
            // A restore only ever happens because recording was interrupted, and
            // nothing on disk says for how long. Reporting the gap when it was
            // brief costs a line of explanation; staying quiet when it was long
            // hides a straight line across ground nobody recorded.
            hadSignalGap = true,
        )
        return true
    }

    /** Called from the UI to abandon the current walk without claiming. */
    fun cancelWalk() {
        _walk.value = WalkState(isTracking = false)
    }

    /**
     * Feed a new GPS fix. This only updates live walk state — the loop is never
     * closed automatically; closing happens when the user presses Stop (see
     * [finishWalk]). [WalkState.readyToClose] reflects whether stopping now would
     * claim a valid loop.
     */
    fun onLocation(
        point: LatLng,
        accuracyMeters: Float? = null,
        /** The fix's own speed, when the provider reports one. */
        speedMps: Float? = null,
        /** Monotonic time of the fix. Null skips motion checks (test taps). */
        atElapsedMs: Long? = null,
        /** Latest activity classification, when available. */
        motion: MotionSample? = null,
        /** Altitude in metres, when the provider reports one. */
        altitudeMeters: Double? = null,
    ) {
        var state = _walk.value
        if (!state.isTracking) return

        // A fix this vague describes nothing, so it must shape nothing: not the
        // path, and not the motion verdict either. Reacquiring after signal loss
        // routinely lands hundreds of metres out, and letting that reach the
        // speed window was on its own enough to void an honest walk. Keep the
        // marker roughly fresh and wait for a fix worth believing.
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS) {
            val toStart = state.start?.let { Geo.distanceMeters(it, point) }
            _walk.value = state.copy(
                current = point,
                distanceToStartMeters = toStart ?: state.distanceToStartMeters,
                accuracyMeters = accuracyMeters,
                readyToClose = !state.motionBlocked &&
                    state.canCloseLoop &&
                    toStart != null &&
                    toStart <= closureRadiusMeters,
            )
            return
        }

        // Only human-powered movement counts. Test mode is exempt: tapped points
        // jump across the map by design and would always look like a vehicle.
        if (atElapsedMs != null && !relaxed) {
            // Losing the signal is not evidence of speed, and it shows up in
            // two different shapes — both of which used to end the walk.
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
            // Ordinary driving stays well below it and is still judged as
            // driving by the gate.
            val silenceMs = lastFixAtElapsedMs?.let { atElapsedMs - it }
            val segmentSpeed = segmentSpeedMps(point, atElapsedMs)
            val reacquired = (silenceMs != null && silenceMs > SIGNAL_GAP_MS) ||
                (segmentSpeed != null && segmentSpeed > REACQUISITION_SPEED_MPS)
            if (reacquired) {
                // Start the speed window over rather than judging the walk on
                // the jump; the gate's grace countdown restarts with it. Also
                // drops the baseline, so the jump itself never becomes a speed
                // sample. `blockedReason` is deliberately left alone: if
                // movement was already being rejected when the signal went, the
                // resume check below still has to answer for the ground between.
                motionGate.reset(state.activityType)
                lastFix = null
                lastFixAtElapsedMs = null
                state = state.copy(hadSignalGap = true)
            }

            val speed = fusedSpeedMps(point, speedMps, atElapsedMs)
            lastFix = point
            lastFixAtElapsedMs = atElapsedMs

            when (val verdict = motionGate.evaluate(atElapsedMs, speed, motion)) {
                is MotionGate.Verdict.Void -> {
                    voidWalk(VoidReason.from(verdict.reason))
                    return
                }

                is MotionGate.Verdict.Blocked -> {
                    // Keep the live marker following the user so the map doesn't
                    // look frozen, but record nothing and make closing impossible.
                    _walk.value = state.copy(
                        current = point,
                        accuracyMeters = accuracyMeters,
                        blockedReason = verdict.reason,
                        blockedSinceElapsedMs = verdict.sinceElapsedMs,
                        readyToClose = false,
                    )
                    return
                }

                MotionGate.Verdict.Allowed -> {
                    if (state.motionBlocked) {
                        // Recording resumes. Nothing was recorded while blocked, so
                        // connecting to the resume point would bridge ground the
                        // user never covered — refuse rather than claim it.
                        val gap = state.path.lastOrNull()
                            ?.let { Geo.distanceMeters(it, point) } ?: 0.0
                        if (gap > MAX_RESUME_GAP_METERS) {
                            voidWalk(VoidReason.UNVERIFIED_GAP)
                            return
                        }
                        state = state.copy(blockedReason = null, blockedSinceElapsedMs = null)
                    }
                    // Credited only once the movement is accepted: time spent
                    // being rejected as a vehicle is not walking time.
                    state = state.copy(movingMs = pause.update(atElapsedMs, speed, motion))
                }
            }
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
                start = point,
                current = point,
                distanceToStartMeters = 0.0,
                startedAtMs = System.currentTimeMillis(),
                accuracyMeters = accuracyMeters,
                elevationGainMeters = climb,
            )
            return
        }

        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter so the path stays clean.
        if (Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            _walk.value = state.copy(
                current = point,
                distanceToStartMeters = toStart,
                accuracyMeters = accuracyMeters,
                readyToClose = state.canCloseLoop && toStart <= closureRadiusMeters,
                elevationGainMeters = climb,
            )
            return
        }

        val newPath = state.path + point
        val distance = state.distanceMeters + Geo.distanceMeters(last, point)
        val leftStart = state.hasLeftStart || toStart > leaveStartRadiusMeters
        val canClose = leftStart && distance >= minPerimeterMeters

        _walk.value = state.copy(
            path = newPath,
            current = point,
            distanceMeters = distance,
            distanceToStartMeters = toStart,
            hasLeftStart = leftStart,
            canCloseLoop = canClose,
            accuracyMeters = accuracyMeters,
            readyToClose = canClose && toStart <= closureRadiusMeters,
            elevationGainMeters = climb,
        )
    }

    /**
     * Called when the user presses Stop. If the loop is [WalkState.readyToClose]
     * it's claimed (opens the modal); otherwise the walk is simply abandoned.
     */
    fun finishWalk() {
        val state = _walk.value
        if (state.readyToClose && state.path.size >= 3) {
            closeLoop(state)
        } else {
            cancelWalk()
        }
    }

    private fun closeLoop(state: WalkState) {
        val path = state.path
        val start = path.first()
        // The closing gap: how far the triggering GPS fix was from the start.
        val closingGap = Geo.distanceMeters(path.last(), start)
        // Snap the loop shut *at the start* rather than at the (slightly off) GPS
        // fix, so the claimed shape and the preview line close on the start point.
        val ring = if (path.size >= 2) path.dropLast(1) + start else path
        val perimeter = Geo.pathLengthMeters(ring)
        // Stop tracking but keep the closed ring on screen as a preview.
        _walk.value = WalkState(
            isTracking = false,
            path = ring,
            start = start,
            hadSignalGap = state.hadSignalGap,
        )
        _pendingClaim.value = PendingClaim(
            id = UUID.randomUUID().toString(),
            ring = ring,
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = perimeter,
            distanceToStartMeters = closingGap,
            closedAtEpochMs = System.currentTimeMillis(),
            startedAtEpochMs = state.startedAtMs,
            elevationGainMeters = state.elevationGainMeters,
            movingMs = state.movingMs,
            hadSignalGap = state.hadSignalGap,
            suggestedName = NameGenerator.random(),
        )
    }

    /**
     * Best estimate of how fast the user is moving right now: the larger of the
     * fix's own speed and the speed implied by the distance since the previous
     * fix. Taking the larger of the two means neither a provider that reports no
     * speed nor one that under-reports it can hide a drive.
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

    /** Throw the walk away: the recorded path no longer reflects a real trip. */
    private fun voidWalk(reason: VoidReason) {
        motionGate.reset()
        elevation.reset()
        pause.reset()
        lastFix = null
        lastFixAtElapsedMs = null
        _walk.value = WalkState(isTracking = false)
        _voidEvents.tryEmit(reason)
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
    // Test mode (map taps): forgiving, so a loop is actually reachable on screen.
    private const val CLOSURE_RADIUS_TEST_METERS = 40.0
    private const val LEAVE_START_TEST_METERS = 40.0
    private const val MIN_PERIMETER_TEST_METERS = 80.0
    /** Fixes closer than this to the previous point are treated as noise. */
    private const val MIN_MOVE_METERS = 4.0

    /**
     * How far recording may resume from where it was suspended. Ground covered
     * while movement was blocked is not recorded, so a longer gap would draw a
     * straight line across land the user never travelled on foot.
     */
    private const val MAX_RESUME_GAP_METERS = 50.0
    /** Fixes worse than this accuracy (meters) are kept off the path. */
    private const val MAX_ACCURACY_METERS = 50f

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
     * use, so it cannot be the drive that [MotionGate] exists to catch, while a
     * reacquisition snap after a frozen fix is typically an order of magnitude
     * beyond it (300 m against a 1 s interval is 300 m/s).
     *
     * Deliberately well clear of [MotionGate.ABSOLUTE_MAX_SPEED_MPS] (20 m/s):
     * everything between the two is still judged as movement and still voids the
     * walk. Only the physically impossible is reclassified as an artefact — and
     * even then the ground it skips is recorded as [WalkState.hadSignalGap]
     * rather than quietly absorbed into the route.
     */
    const val REACQUISITION_SPEED_MPS = 55.0
}
