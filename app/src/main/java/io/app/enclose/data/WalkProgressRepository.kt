package io.app.enclose.data

import io.app.enclose.geo.LatLng

/**
 * One recorded point and the evidence stored with it. Plain values rather than
 * `tracking` types: `tracking` depends on `data`, never the reverse, so the
 * classifier's reading travels as its name.
 */
data class ProgressPoint(
    val position: LatLng,
    /** Epoch ms of the fix; null when it carried no clock. */
    val atEpochMs: Long? = null,
    val afterGap: Boolean = false,
    /** A `tracking.MotionEvidence` name, or null for none. */
    val motion: String? = null,
)

/** One report of steps counted, as the walk in progress stored it. */
data class ProgressSteps(
    /** A `tracking.StepSource` name. */
    val source: String,
    val fromEpochMs: Long,
    val toEpochMs: Long,
    val steps: Int,
)

/** A walk that was in progress when the process died, ready to be resumed. */
data class WalkInProgress(
    val points: List<ProgressPoint>,
    val steps: List<ProgressSteps>,
    val startedAtEpochMs: Long,
    /** Raw [io.app.enclose.tracking.ActivityType] name as it was stored. */
    val activityTypeName: String,
    val elevationGainMeters: Double,
    val movingMs: Long,
) {
    val path: List<LatLng> get() = points.map { it.position }
}

/**
 * The writes [io.app.enclose.tracking.WalkProgressRecorder] makes as a walk
 * happens. Separated from the reads so the recorder — whose job is deciding
 * *when* to write — can be tested without a database.
 */
interface WalkProgressStore {
    suspend fun begin(startedAtEpochMs: Long, activityTypeName: String)
    suspend fun append(points: List<ProgressPoint>)
    suspend fun appendSteps(steps: List<ProgressSteps>)
    suspend fun setTotals(elevationGainMeters: Double, movingMs: Long)
    suspend fun clear()
}

/**
 * Storage for the walk in progress. Writes happen on the recording path — once
 * per accepted fix, and once per step report — so everything here is
 * deliberately small and append-only.
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
    override suspend fun append(points: List<ProgressPoint>) {
        if (points.isEmpty()) return
        dao.insertPoints(points.map { WalkProgressPointEntity.of(it) })
    }

    /** Append newly reported step counts, in order. */
    override suspend fun appendSteps(steps: List<ProgressSteps>) {
        if (steps.isEmpty()) return
        dao.insertSteps(steps.map { WalkProgressStepEntity.of(it) })
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
            points = points.map { it.toProgressPoint() },
            steps = dao.steps().map { it.toProgressSteps() },
            startedAtEpochMs = session.startedAtEpochMs,
            activityTypeName = session.activityType,
            elevationGainMeters = session.elevationGainMeters,
            movingMs = session.movingMs,
        )
    }

    /** Store the running totals, which can't be recomputed from the path. */
    override suspend fun setTotals(elevationGainMeters: Double, movingMs: Long) =
        dao.updateTotals(elevationGainMeters, movingMs)

    /** Forget the walk in progress: it finished, was saved, or was discarded. */
    override suspend fun clear() = dao.clear()
}
