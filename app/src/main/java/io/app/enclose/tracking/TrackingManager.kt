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
    ): Boolean {
        if (path.isEmpty()) return false

        relaxed = false
        motionGate.reset(activityType)
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
    ) {
        var state = _walk.value
        if (!state.isTracking) return

        // Only human-powered movement counts. Test mode is exempt: tapped points
        // jump across the map by design and would always look like a vehicle.
        if (atElapsedMs != null && !relaxed) {
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
                }
            }
        }

        // Very poor fixes shouldn't shape the claimed loop. Once the walk has
        // an anchor, keep the live marker fresh but skip building the path.
        val poorFix = accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS

        // First fix of the walk sets the anchor.
        if (state.path.isEmpty()) {
            // Wait for a usable first fix so the start anchor isn't wildly off.
            if (poorFix) {
                _walk.value = state.copy(current = point, accuracyMeters = accuracyMeters)
                return
            }
            _walk.value = state.copy(
                path = listOf(point),
                start = point,
                current = point,
                distanceToStartMeters = 0.0,
                startedAtMs = System.currentTimeMillis(),
                accuracyMeters = accuracyMeters,
            )
            return
        }

        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter (or reject poor fixes) so the path stays clean.
        if (poorFix || Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            _walk.value = state.copy(
                current = point,
                distanceToStartMeters = toStart,
                accuracyMeters = accuracyMeters,
                readyToClose = state.canCloseLoop && toStart <= closureRadiusMeters,
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
        )
    }

    /**
     * Called when the user presses Stop. If the loop is [WalkState.readyToClose]
     * it's claimed (opens the modal); otherwise the walk is simply abandoned.
     */
    fun finishWalk() {
        val state = _walk.value
        if (state.readyToClose && state.path.size >= 3) {
            closeLoop(state.path)
        } else {
            cancelWalk()
        }
    }

    private fun closeLoop(path: List<LatLng>) {
        val start = path.first()
        // The closing gap: how far the triggering GPS fix was from the start.
        val closingGap = Geo.distanceMeters(path.last(), start)
        // Snap the loop shut *at the start* rather than at the (slightly off) GPS
        // fix, so the claimed shape and the preview line close on the start point.
        val ring = if (path.size >= 2) path.dropLast(1) + start else path
        val perimeter = Geo.pathLengthMeters(ring)
        // Stop tracking but keep the closed ring on screen as a preview.
        _walk.value = WalkState(isTracking = false, path = ring, start = start)
        _pendingClaim.value = PendingClaim(
            id = UUID.randomUUID().toString(),
            ring = ring,
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = perimeter,
            distanceToStartMeters = closingGap,
            closedAtEpochMs = System.currentTimeMillis(),
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
        val previous = lastFix
        val previousAt = lastFixAtElapsedMs
        val segment = if (previous != null && previousAt != null && atElapsedMs > previousAt) {
            Geo.distanceMeters(previous, point) / ((atElapsedMs - previousAt) / 1000.0)
        } else {
            null
        }
        val reported = reportedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        return listOfNotNull(segment, reported).maxOrNull()
    }

    /** Throw the walk away: the recorded path no longer reflects a real trip. */
    private fun voidWalk(reason: VoidReason) {
        motionGate.reset()
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
}
