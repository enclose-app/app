package io.app.enclose.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-first store of every successful closed-loop walk. All writes go to
 * SQLite so the app works fully offline. Each row carries a [SyncStatus] so a
 * future version can push PENDING walks to a remote server (see
 * [io.app.enclose.sync.RemoteSyncApi] for the territory equivalent).
 */
class WalkRepository(private val dao: WalkDao) {

    val walks: Flow<List<Walk>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Persist a walk the moment its loop closes (claim decided later, if at all). */
    suspend fun saveClosed(walk: Walk) = dao.insertIfAbsent(WalkEntity.fromDomain(walk))

    /** Persist/replace a walk the user went on to claim as a territory. */
    suspend fun saveClaimed(walk: Walk) = dao.upsert(WalkEntity.fromDomain(walk))

    suspend fun pending(): List<Walk> = dao.pendingSync().map { it.toDomain() }

    suspend fun markSynced(ids: List<String>) = dao.markSynced(ids)
}
