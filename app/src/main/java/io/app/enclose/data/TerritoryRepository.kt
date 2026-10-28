package io.app.enclose.data

import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single access point for territory data. Everything the app reads/writes goes
 * through here against the local SQLite database, so the app is fully usable
 * offline. Sync to a backend is a separate, best-effort concern (see the sync
 * package) that reads [pending] and calls [markSynced].
 */
class TerritoryRepository(private val dao: TerritoryDao) : SnapStore {

    /** Every claim, newest first. */
    val territories: Flow<List<Territory>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Persist a newly claimed territory (starts life as PENDING sync). One row
     * and nothing else: claims are layers, so a new one changes no other claim.
     */
    suspend fun claim(territory: Territory) {
        dao.upsert(TerritoryEntity.fromDomain(territory))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun pending(): List<Territory> = dao.pendingSync().map { it.toDomain() }

    suspend fun markSynced(ids: List<String>) = dao.markSynced(ids)

    /** Claims whose place isn't fully resolved (see [io.app.enclose.geo.CityResolver]). */
    suspend fun withoutPlace(): List<Territory> = dao.withoutPlace().map { it.toDomain() }

    suspend fun setPlace(id: String, city: String, country: String) =
        dao.updatePlace(id, city, country)

    /** Claims never offered to the route matcher (see [TerritoryDao.withoutSnap]). */
    override suspend fun withoutSnap(): List<Territory> = dao.withoutSnap().map { it.toDomain() }

    /** How many claims a backfill would upload, so the UI can say so before it does. */
    override suspend fun withoutSnapCount(): Int = dao.withoutSnapCount()

    /**
     * Record a match attempt. An empty [ring] records a refusal — the timestamp
     * is written either way, so a walk with no roads to match onto isn't
     * re-uploaded on every backfill for the rest of its life.
     */
    override suspend fun setSnappedRing(id: String, ring: List<LatLng>, atEpochMs: Long) =
        dao.updateSnap(
            id = id,
            json = if (ring.isEmpty()) "" else TerritoryEntity.ringToJson(ring),
            at = atEpochMs,
        )
}
