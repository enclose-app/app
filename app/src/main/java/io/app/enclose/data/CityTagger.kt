package io.app.enclose.data

import io.app.enclose.geo.CityResolver
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.Place
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

    /** Resolve and store the place for one claim. Silent no-op on failure. */
    suspend fun tag(territoryId: String, ring: List<LatLng>) {
        if (ring.isEmpty() || !resolver.isAvailable) return
        val place = resolver.resolvePlace(Geo.centroid(ring)) ?: return
        store(territoryId, place)
    }

    /**
     * Write whatever resolved. The city falls back down the hierarchy (see
     * [io.app.enclose.geo.Place.groupingName]) so a claim in open country still
     * groups somewhere, while the country is stored only when actually named —
     * guessing it from a region name would put false stamps in the passport.
     */
    private suspend fun store(territoryId: String, place: Place) {
        repository.setPlace(
            id = territoryId,
            city = place.groupingName.orEmpty(),
            country = place.country.orEmpty(),
        )
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
            for (territory in repository.withoutPlace()) {
                if (territory.ring.isEmpty()) continue
                val place = resolver.resolvePlace(Geo.centroid(territory.ring))
                if (place == null) {
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
                    continue
                }
                consecutiveFailures = 0
                store(territory.id, place)
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
