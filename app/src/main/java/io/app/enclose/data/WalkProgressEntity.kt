package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.app.enclose.geo.LatLng

/**
 * The walk currently being recorded, so a process death doesn't erase it.
 *
 * There is at most one of these: you can only be on one walk at a time. Only
 * what can't be recomputed is stored — the points (with their times), the step
 * counts, when the walk started, and what the user set out to do. Distance,
 * whether the start zone was left, whether the loop can close and how each
 * stretch counts are all derived from those on restore, so they can't drift out
 * of sync with them.
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
 *
 * Beside the position goes what the walk is judged on at Stop, so a walk
 * restored after a process kill is judged on the same evidence as one that
 * never was: when the fix was taken, whether the line to it crosses unobserved
 * ground, and what the classifier said.
 */
@Entity(tableName = "walk_progress_points")
data class WalkProgressPointEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val lat: Double,
    val lng: Double,
    /** Epoch ms of the fix; null for points stored before times were kept. */
    val atEpochMs: Long? = null,
    /** The line to this point from the one before crosses ground no fix observed. */
    val afterGap: Boolean = false,
    /** A `tracking.MotionEvidence` name, or null for none. Text, so a value added later reads back. */
    val motion: String? = null,
) {
    fun toLatLng(): LatLng = LatLng(lat, lng)

    fun toProgressPoint(): ProgressPoint = ProgressPoint(
        position = toLatLng(),
        atEpochMs = atEpochMs,
        afterGap = afterGap,
        motion = motion,
    )

    companion object {
        fun of(point: ProgressPoint) = WalkProgressPointEntity(
            lat = point.position.lat,
            lng = point.position.lng,
            atEpochMs = point.atEpochMs,
            afterGap = point.afterGap,
            motion = point.motion,
        )
    }
}

/**
 * Steps counted during the walk in progress, one row per report, appended like
 * the points are. Kept for the same reason the point times are: steps are the
 * evidence a stretch faster than walking pace is judged on, and a restored walk
 * that had lost them would be judged on less than it walked with.
 */
@Entity(tableName = "walk_progress_steps")
data class WalkProgressStepEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** A `tracking.StepSource` name. */
    val source: String,
    val fromEpochMs: Long,
    val toEpochMs: Long,
    val steps: Int,
) {
    fun toProgressSteps(): ProgressSteps = ProgressSteps(source, fromEpochMs, toEpochMs, steps)

    companion object {
        fun of(s: ProgressSteps) = WalkProgressStepEntity(
            source = s.source,
            fromEpochMs = s.fromEpochMs,
            toEpochMs = s.toEpochMs,
            steps = s.steps,
        )
    }
}
