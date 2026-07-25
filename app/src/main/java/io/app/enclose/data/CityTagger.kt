package io.app.enclose.data

import io.app.enclose.geo.CityResolver
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps [Territory.city] filled in.
 *
 * Reverse geocoding needs a network, and claiming a territory must not, so the
 * city is resolved *after* the claim is saved and simply retried later if it
 * fails — [backfill] catches up on everything still blank, including claims made
 * before this existed and claims made offline.
 *
 * Shared app-wide (see [io.app.enclose.EncloseApp]) so the map and profile
 * screens can't run competing backfills.
 */
class CityTagger(
    private val repository: TerritoryRepository,
    private val resolver: CityResolver,
) {

    private val lock = Mutex()

    /** Resolve and store the city for one claim. Silent no-op on failure. */
    suspend fun tag(territoryId: String, ring: List<LatLng>) {
        if (ring.isEmpty() || !resolver.isAvailable) return
        val city = resolver.resolve(Geo.centroid(ring)) ?: return
        repository.setCity(territoryId, city)
    }

    /**
     * Fill in every claim still missing a city. Cheap and idempotent when
     * there's nothing to do, so it's safe to call whenever a screen that shows
     * cities opens.
     *
     * Gives up after [MAX_CONSECUTIVE_FAILURES] failures in a row rather than
     * walking the whole list: that many misses in sequence means the geocoder is
     * unreachable, not that these particular spots are unnamed, and the next
     * call will pick up where this one stopped.
     */
    suspend fun backfill() {
        if (!resolver.isAvailable) return
        // A second caller would only re-resolve what the first is already doing.
        if (!lock.tryLock()) return
        try {
            var consecutiveFailures = 0
            for (territory in repository.withoutCity()) {
                if (territory.ring.isEmpty()) continue
                val city = resolver.resolve(Geo.centroid(territory.ring))
                if (city == null) {
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
                    continue
                }
                consecutiveFailures = 0
                repository.setCity(territory.id, city)
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
