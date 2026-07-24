package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    )

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

    /** When true, use relaxed thresholds so a tap-tested loop can still close. */
    private var relaxed = false

    /** Called from the UI when the user taps "Start walk" (or the first test tap). */
    fun startWalk(relaxedThresholds: Boolean = false) {
        relaxed = relaxedThresholds
        _walk.value = WalkState(isTracking = true)
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
    fun onLocation(point: LatLng) {
        val state = _walk.value
        if (!state.isTracking) return

        // First fix of the walk sets the anchor.
        if (state.path.isEmpty()) {
            _walk.value = state.copy(
                path = listOf(point),
                start = point,
                current = point,
                distanceToStartMeters = 0.0,
            )
            return
        }

        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter so a stationary phone doesn't inflate the path.
        if (Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            _walk.value = state.copy(
                current = point,
                distanceToStartMeters = toStart,
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
}
