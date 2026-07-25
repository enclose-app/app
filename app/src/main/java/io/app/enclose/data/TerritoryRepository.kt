package io.app.enclose.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single access point for territory data. Everything the app reads/writes goes
 * through here against the local SQLite database, so the app is fully usable
 * offline. Sync to a backend is a separate, best-effort concern (see the sync
 * package) that reads [pending] and calls [markSynced].
 */
class TerritoryRepository(private val dao: TerritoryDao) {

    /** Claims still standing. Conquered ones are excluded — see [conquered]. */
    val territories: Flow<List<Territory>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    /** Claims swallowed whole by a later walk, most recently fallen first. */
    val conquered: Flow<List<Territory>> =
        dao.observeConquered().map { list -> list.map { it.toDomain() } }

    /** Persist a newly claimed territory (starts life as PENDING sync). */
    suspend fun claim(territory: Territory) {
        dao.upsert(TerritoryEntity.fromDomain(territory))
    }

    /**
     * Persist a new claim and everything it took ground from, atomically.
     * See [TerritoryDao.applyClaim] for why these can't be separate writes.
     */
    suspend fun applyClaim(claim: Territory, carved: List<Territory>) {
        dao.applyClaim(
            claim = TerritoryEntity.fromDomain(claim),
            carved = carved.map { TerritoryEntity.fromDomain(it) },
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun pending(): List<Territory> = dao.pendingSync().map { it.toDomain() }

    suspend fun markSynced(ids: List<String>) = dao.markSynced(ids)

    /** Claims whose place isn't fully resolved (see [io.app.enclose.geo.CityResolver]). */
    suspend fun withoutPlace(): List<Territory> = dao.withoutPlace().map { it.toDomain() }

    suspend fun setPlace(id: String, city: String, country: String) =
        dao.updatePlace(id, city, country)
}
