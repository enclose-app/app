package io.app.enclose.tracking

import io.app.enclose.data.ProgressPoint
import io.app.enclose.data.ProgressSteps
import io.app.enclose.data.WalkProgressStore
import kotlinx.coroutines.flow.Flow

/**
 * Mirrors the walk in progress to storage as it happens.
 *
 * [TrackingManager] holds the walk in memory and has no database of its own by
 * design, which is what made a process death cost the whole walk: the service
 * would be restarted by the system, the manager would come back empty, and
 * every fix after that was dropped by its `isTracking` guard while the
 * notification still claimed to be recording. This watches the manager from the
 * outside and writes the points down, leaving it free of Android and DB types.
 *
 * Only new points and new step reports are written — both are append-only, so
 * each fix costs one small insert no matter how long the walk has been going.
 * Each point goes down with its time and the evidence it was judged on, so a
 * restored walk is judged on exactly what an uninterrupted one would have been.
 */
class WalkProgressRecorder(private val repository: WalkProgressStore) {

    /** How many points of the current session are already on disk. */
    private var persisted = 0

    /** How many step reports of the current session are already on disk. */
    private var persistedSteps = 0
    private var sessionOpen = false

    /** Last totals written, so unchanged ones don't cost a write per fix. */
    private var persistedGainMeters = 0.0
    private var persistedMovingMs = 0L

    /**
     * Take ownership of a session just restored from disk. Without this the
     * recorder would treat the restored path as brand new and write every point
     * a second time.
     */
    fun adopt(
        pointCount: Int,
        elevationGainMeters: Double = 0.0,
        movingMs: Long = 0L,
        stepCount: Int = 0,
    ) {
        sessionOpen = true
        persisted = pointCount
        persistedSteps = stepCount
        persistedGainMeters = elevationGainMeters
        persistedMovingMs = movingMs
    }

    /** Follow [states] until cancelled, keeping storage in step with it. */
    suspend fun record(states: Flow<TrackingManager.WalkState>) {
        states.collect { state -> onState(state) }
    }

    /** Visible for tests: the whole decision, one state at a time. */
    internal suspend fun onState(state: TrackingManager.WalkState) {
        if (!state.isTracking) {
            // Finished, saved, or discarded — either way there is nothing left
            // to resume, and a stale session would be offered to the next walk.
            if (sessionOpen) {
                sessionOpen = false
                persisted = 0
                persistedSteps = 0
                persistedGainMeters = 0.0
                persistedMovingMs = 0L
                repository.clear()
            }
            return
        }

        // Wait for the first accepted fix: a walk with no points has nothing
        // worth restoring, and startedAtMs isn't set until one lands.
        if (state.path.isEmpty()) return

        if (!sessionOpen) {
            repository.begin(
                startedAtEpochMs = state.startedAtMs ?: System.currentTimeMillis(),
                activityTypeName = state.activityType.name,
            )
            sessionOpen = true
            persisted = 0
            persistedSteps = 0
            persistedGainMeters = 0.0
            persistedMovingMs = 0L
        }

        if (state.path.size > persisted) {
            repository.append(
                (persisted until state.path.size).map { i ->
                    val info = state.track.getOrNull(i)
                    ProgressPoint(
                        position = state.path[i],
                        atEpochMs = info?.atEpochMs,
                        afterGap = info?.afterGap ?: false,
                        motion = info?.motion?.name,
                    )
                },
            )
            persisted = state.path.size
        }

        if (state.steps.size > persistedSteps) {
            repository.appendSteps(
                state.steps.subList(persistedSteps, state.steps.size).map {
                    ProgressSteps(it.source.name, it.fromEpochMs, it.toEpochMs, it.steps)
                },
            )
            persistedSteps = state.steps.size
        }

        // Only when something actually moves: the elevation noise gate leaves
        // most fixes unchanged, and a stationary walker's moving time doesn't
        // advance either — neither should cost a write.
        if (state.elevationGainMeters != persistedGainMeters || state.movingMs != persistedMovingMs) {
            repository.setTotals(state.elevationGainMeters, state.movingMs)
            persistedGainMeters = state.elevationGainMeters
            persistedMovingMs = state.movingMs
        }
    }
}
