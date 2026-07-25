package io.app.enclose.data

import io.app.enclose.geo.LatLng

/** A walk that was in progress when the process died, ready to be resumed. */
data class WalkInProgress(
    val path: List<LatLng>,
    val startedAtEpochMs: Long,
    /** Raw [io.app.enclose.tracking.ActivityType] name as it was stored. */
    val activityTypeName: String,
)

/**
 * The writes [io.app.enclose.tracking.WalkProgressRecorder] makes as a walk
 * happens. Separated from the reads so the recorder — whose job is deciding
 * *when* to write — can be tested without a database.
 */
interface WalkProgressStore {
    suspend fun begin(startedAtEpochMs: Long, activityTypeName: String)
    suspend fun append(points: List<LatLng>)
    suspend fun clear()
}

/**
 * Storage for the walk in progress. Writes happen on the recording path — once
 * per accepted fix — so everything here is deliberately small and append-only.
 */
class WalkProgressRepository(private val dao: WalkProgressDao) : WalkProgressStore {

    /** Begin recording, discarding any session left over from a dead process. */
    override suspend fun begin(startedAtEpochMs: Long, activityTypeName: String) {
        dao.begin(
            WalkProgressEntity(
                startedAtEpochMs = startedAtEpochMs,
                activityType = activityTypeName,
            ),
        )
    }

    /** Append newly walked points, in order. */
    override suspend fun append(points: List<LatLng>) {
        if (points.isEmpty()) return
        dao.insertPoints(points.map { WalkProgressPointEntity.of(it) })
    }

    /**
     * The walk left behind by a dead process, or null if there is none worth
     * resuming. A session with no points hasn't recorded anything, so there is
     * nothing to restore and it's treated as absent.
     */
    suspend fun load(): WalkInProgress? {
        val session = dao.session() ?: return null
        val points = dao.points()
        if (points.isEmpty()) return null
        return WalkInProgress(
            path = points.map { it.toLatLng() },
            startedAtEpochMs = session.startedAtEpochMs,
            activityTypeName = session.activityType,
        )
    }

    /** Forget the walk in progress: it finished, was abandoned, or was voided. */
    override suspend fun clear() = dao.clear()
}
