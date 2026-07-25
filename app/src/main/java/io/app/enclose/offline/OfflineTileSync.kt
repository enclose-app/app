package io.app.enclose.offline

import io.app.enclose.data.OfflineRegionDao
import io.app.enclose.data.OfflineRegionEntity
import io.app.enclose.data.TerritoryRepository
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the cached map in step with where the user has claims.
 *
 * Runs from [OfflineTilesWorker] under an unmetered-network constraint: this
 * downloads tens of megabytes per city, and doing that on someone's mobile data
 * because they happened to claim a loop would be indefensible however useful the
 * result is.
 */
class OfflineTileSync(
    private val territories: TerritoryRepository,
    private val dao: OfflineRegionDao,
    private val cache: OfflineTileCache,
    private val budgetBytes: Long = OfflineTilePlanner.DEFAULT_BUDGET_BYTES,
) {

    private val lock = Mutex()

    /**
     * Download anything newly claimed-in, then evict down to budget.
     *
     * Cities already cached are left alone rather than re-downloaded: claims
     * grow within a city far more often than they leave its cached box, and
     * re-downloading on every claim would spend the user's disk and battery to
     * fetch tiles that are already there.
     */
    suspend fun sync(styleUrl: String, pixelRatio: Float) {
        // A second run would fight the first over the same regions.
        if (!lock.tryLock()) return
        try {
            val planned = OfflineTilePlanner.plan(territories.snapshot())
            val known = dao.all().associateBy { it.city }

            for (region in planned) {
                if (known.containsKey(region.city)) continue
                downloadAndRecord(region, styleUrl, pixelRatio)
            }

            evictOverBudget(keep = planned.map { it.city }.toSet())
        } finally {
            lock.unlock()
        }
    }

    private suspend fun downloadAndRecord(
        region: PlannedRegion,
        styleUrl: String,
        pixelRatio: Float,
    ) {
        // Recorded before the download so a process death mid-download leaves a
        // row to reconcile rather than an orphaned MapLibre region.
        val result = cache.download(region, styleUrl, pixelRatio) ?: return
        val (regionId, outcome) = result
        dao.upsert(
            OfflineRegionEntity(
                city = region.city,
                regionId = regionId,
                sizeBytes = outcome.sizeBytes,
                completedAtEpochMs = if (outcome.complete) System.currentTimeMillis() else null,
            ),
        )
    }

    private suspend fun evictOverBudget(keep: Set<String>) {
        val cached = dao.all().map {
            CachedRegion(
                city = it.city,
                sizeBytes = it.sizeBytes,
                visitCount = it.visitCount,
                lastVisitedAtEpochMs = it.lastVisitedAtEpochMs,
            )
        }
        for (city in OfflineTilePlanner.evictions(cached, budgetBytes, keep)) {
            val row = dao.byCity(city) ?: continue
            // Only forget the row once the tiles are really gone, so a failed
            // delete doesn't leak disk that nothing is tracking any more.
            if (cache.delete(row.regionId)) dao.delete(city)
        }
    }

    /**
     * Count a visit to whichever cached city contains [point].
     *
     * Cheap enough to call whenever the map camera settles, which is what makes
     * "least visited" mean where the user actually goes rather than where they
     * once claimed something.
     */
    suspend fun recordVisit(point: LatLng) {
        val planned = OfflineTilePlanner.plan(territories.snapshot())
        val city = planned.firstOrNull { OfflineTilePlanner.contains(it, point) } ?: return
        dao.recordVisit(city.city, System.currentTimeMillis())
    }

    private suspend fun TerritoryRepository.snapshot() = this.territories.first()
}
