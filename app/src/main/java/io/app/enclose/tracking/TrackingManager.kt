package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.PathSpikes
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
        /**
         * Warnings this walk has used, out of [MotionGate.MAX_STRIKES]. A strike
         * is spent when movement stays blocked past the grace window, or when
         * recording resumes further from where it stopped than the recorded path
         * can account for. The walk survives every strike but the last.
         */
        val strikes: Int = 0,
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
    ) {
        /** True while a vehicle (or implausible speed) is suspending recording. */
        val motionBlocked: Boolean get() = blockedReason != null

        /** Warnings left before the next one ends the walk. */
        val strikesRemaining: Int get() = (MotionGate.MAX_STRIKES - strikes).coerceAtLeast(0)
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

    /** What a voided walk had recorded, handed over before the walk is reset. */
    data class VoidedRecording(
        val id: String,
        val path: List<LatLng>,
        val startedAtEpochMs: Long?,
        val voidedAtEpochMs: Long,
        val distanceMeters: Double,
        val reason: VoidReason,
    )

    /**
     * Every void that had recorded any ground, with that ground. The void stands
     * — nothing is claimed — but the path is kept: most voids are honest walks the
     * classifier got wrong, and the walking happened either way. Whoever owns
     * persistence saves it (EncloseApp); this object still has no DB of its own.
     */
    private val _voidedRecordings = MutableSharedFlow<VoidedRecording>(extraBufferCapacity = 4)
    val voidedRecordings: SharedFlow<VoidedRecording> = _voidedRecordings.asSharedFlow()

    /**
     * Emitted when the recorder could not be started, or stopped being able to
     * run. Separate from [voidEvents]: nothing was walked and nothing was thrown
     * away — the app simply cannot do the thing it just said it was doing, and
     * has to say so.
     */
    private val _recordingFailures = MutableSharedFlow<RecordingFailure>(extraBufferCapacity = 4)
    val recordingFailures: SharedFlow<RecordingFailure> = _recordingFailures.asSharedFlow()

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

    /** A fix too far from the path to append yet; see [FixPlausibility]. */
    private data class HeldFix(val point: LatLng, val atElapsedMs: Long, val accuracyMeters: Float?)
    private var heldFix: HeldFix? = null

    /**
     * When the path's last point was last confirmed by a fix, and how accurate
     * that fix was — the baseline a held fix is judged against. Null when there is
     * no observed baseline (a new or restored walk), and then nothing is held.
     */
    private var trustedAtElapsedMs: Long? = null
    private var trustedAccuracyMeters: Float? = null

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
        clearHeld()
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
        clearHeld()

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

        // A fix arriving is the only proof the recorder works, so it is what
        // clears a reported failure — not the notice being dismissed on screen.
        if (state.recordingFailure != null) state = state.copy(recordingFailure = null)

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

        // Set when this fix arrived impossibly fast from the previous one.
        var snapped = false

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
                // The strikes stay banked too — silence is not an amnesty.
                motionGate.clearSpeedWindow()
                lastFix = null
                lastFixAtElapsedMs = null
                // Silence is a gap whatever comes next. A snap is only one if the
                // fix it snapped to is kept — a spike that is dropped below
                // leaves no hole in the recording.
                if (silenceMs != null && silenceMs > SIGNAL_GAP_MS) {
                    state = state.copy(hadSignalGap = true)
                } else {
                    snapped = true
                }
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

                is MotionGate.Verdict.Strike -> {
                    // A warning, not an ending. The movement is still being
                    // rejected — `blockedReason` deliberately stays set — so the
                    // resume check below still has to account for the ground
                    // covered in the meantime. What changes is only that the
                    // countdown starts again, against one fewer strike.
                    _walk.value = state.copy(
                        current = point,
                        accuracyMeters = accuracyMeters,
                        blockedReason = verdict.reason,
                        blockedSinceElapsedMs = atElapsedMs,
                        strikes = verdict.count,
                        readyToClose = false,
                    )
                    return
                }

                MotionGate.Verdict.Allowed -> {
                    if (state.motionBlocked) {
                        // Recording resumes. Nothing was recorded while blocked, so
                        // connecting to the resume point would bridge ground the
                        // user never covered.
                        val gap = state.path.lastOrNull()
                            ?.let { Geo.distanceMeters(it, point) } ?: 0.0
                        when {
                            // Too far to be anything but a ride. No number of
                            // warnings makes a straight line across half a city
                            // into ground someone walked.
                            gap > MAX_UNVERIFIED_GAP_METERS -> {
                                voidWalk(VoidReason.UNVERIFIED_GAP)
                                return
                            }
                            // Far enough that the route now contains ground the
                            // recording never saw: a warning, and the outline is
                            // marked as estimated, rather than an hour thrown out.
                            gap > MAX_RESUME_GAP_METERS -> {
                                val count = motionGate.bankStrike()
                                if (count >= MotionGate.MAX_STRIKES) {
                                    voidWalk(VoidReason.UNVERIFIED_GAP)
                                    return
                                }
                                state = state.copy(strikes = count, hadSignalGap = true)
                            }
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
            trustFix(atElapsedMs, accuracyMeters)
            return
        }

        // A fix nobody could have reached from the path is held back until the
        // next one says what it was — see [FixPlausibility]. Only timed fixes are
        // judged: taps and imports have no clock to judge them by.
        if (atElapsedMs != null && !relaxed) {
            val held = heldFix
            heldFix = null
            val anchor = state.path.last()
            val anchorAt = trustedAtElapsedMs
            val jumped = anchorAt != null && FixPlausibility.isJump(
                anchor, anchorAt, trustedAccuracyMeters, point, atElapsedMs, accuracyMeters,
            )
            when {
                // Back where the path was: the held fix was a spike. Drop it, and
                // this fix's jump *from* the spike is no gap in the recording.
                !jumped -> if (held != null) snapped = false

                // The position really did move — a frozen fix snapping to the
                // truth. The held fix goes on the path first, and the straight
                // line to it crosses ground nobody observed.
                held != null && !FixPlausibility.isJump(
                    held.point, held.atElapsedMs, held.accuracyMeters,
                    point, atElapsedMs, accuracyMeters,
                ) -> {
                    state = extend(
                        state.copy(hadSignalGap = true),
                        held.point,
                        held.accuracyMeters,
                        state.elevationGainMeters,
                    )
                    trustFix(held.atElapsedMs, held.accuracyMeters)
                }

                // A jump from everything trusted so far: hold it.
                else -> {
                    heldFix = HeldFix(point, atElapsedMs, accuracyMeters)
                    _walk.value = state.copy(accuracyMeters = accuracyMeters, elevationGainMeters = climb)
                    return
                }
            }
        }

        if (snapped) state = state.copy(hadSignalGap = true)
        _walk.value = extend(state, point, accuracyMeters, climb)
        trustFix(atElapsedMs, accuracyMeters)
    }

    /**
     * [state] with [point] added to the path, or — inside [MIN_MOVE_METERS] of the
     * last point — with only the live figures moved on.
     */
    private fun extend(state: WalkState, point: LatLng, accuracyMeters: Float?, climb: Double): WalkState {
        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter so the path stays clean.
        if (Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            return state.copy(
                current = point,
                distanceToStartMeters = toStart,
                accuracyMeters = accuracyMeters,
                readyToClose = state.canCloseLoop && toStart <= closureRadiusMeters,
                elevationGainMeters = climb,
            )
        }

        val newPath = state.path + point
        val distance = state.distanceMeters + Geo.distanceMeters(last, point)
        val leftStart = state.hasLeftStart || toStart > leaveStartRadiusMeters
        val canClose = leftStart && distance >= minPerimeterMeters

        return state.copy(
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

    /** Record that the path's last point was still where the walker was at [atElapsedMs]. */
    private fun trustFix(atElapsedMs: Long?, accuracyMeters: Float?) {
        trustedAtElapsedMs = atElapsedMs
        trustedAccuracyMeters = accuracyMeters
    }

    /** Forget the held fix and what the path was last trusted at: a new walk, or none. */
    private fun clearHeld() {
        heldFix = null
        trustedAtElapsedMs = null
        trustedAccuracyMeters = null
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
        // A spike the live hold let through — two bad fixes in a row, or one
        // already on disk in a restored walk — would otherwise be claimed.
        val path = PathSpikes.remove(state.path)
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

    /**
     * End the walk without a claim: the recorded path no longer reflects a real
     * trip. The path itself is handed to [voidedRecordings] first, not discarded.
     */
    private fun voidWalk(reason: VoidReason) {
        // Taken before the reset below, which is what used to erase it.
        val walked = _walk.value
        if (walked.path.size >= 2) {
            _voidedRecordings.tryEmit(
                VoidedRecording(
                    id = UUID.randomUUID().toString(),
                    path = walked.path,
                    startedAtEpochMs = walked.startedAtMs,
                    voidedAtEpochMs = System.currentTimeMillis(),
                    distanceMeters = walked.distanceMeters,
                    reason = reason,
                ),
            )
        }
        motionGate.reset()
        elevation.reset()
        pause.reset()
        lastFix = null
        lastFixAtElapsedMs = null
        clearHeld()
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
    // Test mode (map taps) and imported tracks: forgiving, so a loop is actually
    // reachable on screen, and a recorded track stopped a few dozen metres short
    // of its start still closes. Neither runs the motion gate, so a wider circle
    // here gives away no anti-cheat protection.
    private const val CLOSURE_RADIUS_TEST_METERS = 75.0
    private const val LEAVE_START_TEST_METERS = 40.0
    private const val MIN_PERIMETER_TEST_METERS = 80.0
    /** Fixes closer than this to the previous point are treated as noise. */
    private const val MIN_MOVE_METERS = 4.0

    /**
     * How far recording may resume from where it was suspended before it costs
     * the walk anything. Ground covered while movement was blocked is not
     * recorded, so a longer gap draws a straight line across land nobody
     * observed.
     *
     * Raised from 50 m with the move to strikes. Fifty metres is inside the error
     * of a fix reacquired after a blocked stretch, so honest walks were being
     * voided by the recovery rather than by the gap.
     */
    private const val MAX_RESUME_GAP_METERS = 150.0

    /**
     * The gap that no warning covers. Past a kilometre of unrecorded ground the
     * path is not a walk with a hole in it — it is two walks with a drive
     * between them, and claiming the polygon they enclose would be claiming the
     * drive.
     */
    private const val MAX_UNVERIFIED_GAP_METERS = 1_000.0
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
