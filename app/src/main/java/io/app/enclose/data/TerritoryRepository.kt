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

    val territories: Flow<List<Territory>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Persist a newly claimed territory (starts life as PENDING sync). */
    suspend fun claim(territory: Territory) {
        dao.upsert(TerritoryEntity.fromDomain(territory))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun pending(): List<Territory> = dao.pendingSync().map { it.toDomain() }

    suspend fun markSynced(ids: List<String>) = dao.markSynced(ids)
}
