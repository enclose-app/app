package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.app.enclose.geo.LatLng

/**
 * The walk currently being recorded, so a process death doesn't erase it.
 *
 * There is at most one of these: you can only be on one walk at a time. Only
 * what can't be recomputed is stored — the points, when the walk started, and
 * what the user set out to do. Distance, whether the start zone was left, and
 * whether the loop can close are all derived from the path on restore, so they
 * can't drift out of sync with it.
 */
@Entity(tableName = "walk_progress")
data class WalkProgressEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    val startedAtEpochMs: Long,
    /** [io.app.enclose.tracking.ActivityType] name; unknown values fall back to WALK. */
    val activityType: String,
    /**
     * Running climb. Unlike distance this can't be recomputed from the stored
     * path, because altitude isn't kept per point — so it's carried here.
     */
    val elevationGainMeters: Double = 0.0,
    /** Running moving time; like climb, it can't be recomputed from the path. */
    val movingMs: Long = 0L,
) {
    companion object {
        /** The fixed primary key of the one-and-only in-progress walk row. */
        const val SINGLETON_ID = "current"
    }
}

/**
 * One recorded point of the walk in progress, appended as it happens.
 *
 * Append-only on purpose: rewriting the whole path on every fix would cost more
 * with each step taken, exactly when the walk is longest and has the most to
 * lose. [seq] preserves the walked order.
 */
@Entity(tableName = "walk_progress_points")
data class WalkProgressPointEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val lat: Double,
    val lng: Double,
) {
    fun toLatLng(): LatLng = LatLng(lat, lng)

    companion object {
        fun of(point: LatLng) = WalkProgressPointEntity(lat = point.lat, lng = point.lng)
    }
}
