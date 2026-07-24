package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    )

    /** A closed loop awaiting the user's decision to claim (with name/color). */
    data class PendingClaim(
        val ring: List<LatLng>,
        val areaSqMeters: Double,
        val perimeterMeters: Double,
        /** How far the closing point landed from the start (the closing gap). */
        val distanceToStartMeters: Double,
        val suggestedName: String,
    )

    private val _walk = MutableStateFlow(WalkState())
    val walk: StateFlow<WalkState> = _walk.asStateFlow()

    private val _pendingClaim = MutableStateFlow<PendingClaim?>(null)
    val pendingClaim: StateFlow<PendingClaim?> = _pendingClaim.asStateFlow()

    /** Called from the UI when the user taps "Start walk". */
    fun startWalk() {
        _walk.value = WalkState(isTracking = true)
    }

    /** Called from the UI to abandon the current walk without claiming. */
    fun cancelWalk() {
        _walk.value = WalkState(isTracking = false)
    }

    /**
     * Feed a new GPS fix. Returns true if this fix closed the loop (the caller
     * — the service — should then stop location updates).
     */
    fun onLocation(point: LatLng): Boolean {
        val state = _walk.value
        if (!state.isTracking) return false

        // First fix of the walk sets the anchor.
        if (state.path.isEmpty()) {
            _walk.value = state.copy(
                path = listOf(point),
                start = point,
                current = point,
                distanceToStartMeters = 0.0,
            )
            return false
        }

        val last = state.path.last()
        val start = state.start!!
        val toStart = Geo.distanceMeters(start, point)

        // Ignore GPS jitter so a stationary phone doesn't inflate the path — but
        // still allow closing if we're already able to and this fix is at start.
        if (Geo.distanceMeters(last, point) < MIN_MOVE_METERS) {
            _walk.value = state.copy(current = point, distanceToStartMeters = toStart)
            if (state.canCloseLoop && toStart <= CLOSURE_RADIUS_METERS) {
                closeLoop(state.path)
                return true
            }
            return false
        }

        val newPath = state.path + point
        val distance = state.distanceMeters + Geo.distanceMeters(last, point)
        val leftStart = state.hasLeftStart || toStart > LEAVE_START_RADIUS_METERS
        val canClose = leftStart && distance >= MIN_PERIMETER_METERS

        _walk.value = state.copy(
            path = newPath,
            current = point,
            distanceMeters = distance,
            distanceToStartMeters = toStart,
            hasLeftStart = leftStart,
            canCloseLoop = canClose,
        )

        if (canClose && toStart <= CLOSURE_RADIUS_METERS) {
            closeLoop(newPath)
            return true
        }
        return false
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
            ring = ring,
            areaSqMeters = Geo.polygonAreaSqMeters(ring),
            perimeterMeters = perimeter,
            distanceToStartMeters = closingGap,
            suggestedName = NameGenerator.random(),
        )
    }

    /** Clears the pending claim and resets the map to idle (claim or discard). */
    fun clearPending() {
        _pendingClaim.value = null
        _walk.value = WalkState()
    }

    // --- Tuning ---------------------------------------------------------------
    /** How close to the start counts as "closing the loop". */
    const val CLOSURE_RADIUS_METERS = 10.0
    /** Must get at least this far from start before a close can count. */
    const val LEAVE_START_RADIUS_METERS = 80.0
    /** Minimum walked distance before a loop may be claimed. */
    const val MIN_PERIMETER_METERS = 200.0
    /** Fixes closer than this to the previous point are treated as noise. */
    private const val MIN_MOVE_METERS = 4.0
}
