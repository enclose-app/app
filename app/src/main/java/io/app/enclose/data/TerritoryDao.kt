package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * An abstract class rather than an interface so [applyClaim] can wrap several
 * writes in one Room [Transaction].
 */
@Dao
abstract class TerritoryDao {

    /** Claims still standing — everything the map and the list show. */
    @Query(
        "SELECT * FROM territories WHERE conqueredAtEpochMs IS NULL " +
            "ORDER BY claimedAtEpochMs DESC",
    )
    abstract fun observeActive(): Flow<List<TerritoryEntity>>

    /** Claims a later walk swallowed whole, most recently fallen first. */
    @Query(
        "SELECT * FROM territories WHERE conqueredAtEpochMs IS NOT NULL " +
            "ORDER BY conqueredAtEpochMs DESC",
    )
    abstract fun observeConquered(): Flow<List<TerritoryEntity>>

    @Query("SELECT * FROM territories WHERE syncStatus = 'PENDING'")
    abstract suspend fun pendingSync(): List<TerritoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: TerritoryEntity)

    @Query("UPDATE territories SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    abstract suspend fun markSynced(ids: List<String>)

    /** Claims still waiting on reverse geocoding, oldest first. */
    @Query("SELECT * FROM territories WHERE city = '' ORDER BY claimedAtEpochMs ASC")
    abstract suspend fun withoutCity(): List<TerritoryEntity>

    /**
     * Deliberately leaves syncStatus alone: the city is derived locally from
     * coordinates the backend already has, so resolving it is not an edit the
     * user made and shouldn't queue an upload.
     */
    @Query("UPDATE territories SET city = :city WHERE id = :id")
    abstract suspend fun updateCity(id: String, city: String)

    @Query("DELETE FROM territories WHERE id = :id")
    abstract suspend fun delete(id: String)

    /**
     * Save a new claim together with everything it took ground from.
     *
     * One transaction, because these writes only make sense together: carving
     * older claims is justified *by* the new one, so a process death between
     * the two used to leave territories already cut down for a claim that was
     * never recorded — land gone, with nothing to show for it. The new claim is
     * written first so even a torn transaction log favours the record existing.
     */
    @Transaction
    open suspend fun applyClaim(claim: TerritoryEntity, carved: List<TerritoryEntity>) {
        upsert(claim)
        carved.forEach { upsert(it) }
    }
}
