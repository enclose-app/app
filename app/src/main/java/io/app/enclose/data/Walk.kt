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
    /** Whether this walk was claimed as a territory. */
    val claimed: Boolean,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
)
