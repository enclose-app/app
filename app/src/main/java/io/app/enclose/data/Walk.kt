package io.app.enclose.data

import io.app.enclose.geo.LatLng

/**
 * A successful walk: a loop the user closed. Every closed loop is recorded here
 * the moment it closes, whether or not the user goes on to claim it as a
 * territory ([claimed]). Persisted locally so the app works fully offline;
 * [syncStatus] lets a future version push these to a remote server.
 */
data class Walk(
    val id: String,
    /** The closed boundary ring (last point equals the start). */
    val ring: List<LatLng>,
    val areaSqMeters: Double,
    val perimeterMeters: Double,
    /** Closing gap: how far the triggering fix was from the start. */
    val distanceToStartMeters: Double,
    val closedAtEpochMs: Long,
    /**
     * When the first fix landed. Null for walks recorded before this was kept,
     * which is why [durationMs] is nullable rather than a subtraction at the
     * call site.
     */
    val startedAtEpochMs: Long? = null,
    /** Confirmed climb in metres; see [io.app.enclose.tracking.ElevationAccumulator]. */
    val elevationGainMeters: Double = 0.0,
    /**
     * Time spent actually moving, excluding stops. Null for walks recorded
     * before this was measured — distinct from zero, which would claim the
     * walker never moved.
     */
    val movingMs: Long? = null,
    /** Whether this walk was claimed as a territory. */
    val claimed: Boolean,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
) {
    /** How long the walk took end to end, or null when the start wasn't recorded. */
    val durationMs: Long?
        get() = startedAtEpochMs?.let { (closedAtEpochMs - it).takeIf { d -> d > 0 } }

    /**
     * The duration pace should be measured against: moving time when it was
     * recorded, otherwise the wall-clock duration. Falling back keeps old walks
     * showing a pace rather than nothing.
     */
    val pacingMs: Long?
        get() = movingMs?.takeIf { it > 0 } ?: durationMs
}
