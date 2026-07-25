package io.app.enclose.tracking

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
 * Only new points are written — the path is append-only, so each fix costs one
 * small insert no matter how long the walk has been going.
 */
class WalkProgressRecorder(private val repository: WalkProgressStore) {

    /** How many points of the current session are already on disk. */
    private var persisted = 0
    private var sessionOpen = false

    /** Last totals written, so unchanged ones don't cost a write per fix. */
    private var persistedGainMeters = 0.0
    private var persistedMovingMs = 0L

    /**
     * Take ownership of a session just restored from disk. Without this the
     * recorder would treat the restored path as brand new and write every point
     * a second time.
     */
    fun adopt(pointCount: Int, elevationGainMeters: Double = 0.0, movingMs: Long = 0L) {
        sessionOpen = true
        persisted = pointCount
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
            // Finished, abandoned, or voided — either way there is nothing left
            // to resume, and a stale session would be offered to the next walk.
            if (sessionOpen) {
                sessionOpen = false
                persisted = 0
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
            persistedGainMeters = 0.0
            persistedMovingMs = 0L
        }

        if (state.path.size > persisted) {
            repository.append(state.path.subList(persisted, state.path.size))
            persisted = state.path.size
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
