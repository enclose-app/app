package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TerritoryDao {

    @Query("SELECT * FROM territories ORDER BY claimedAtEpochMs DESC")
    fun observeAll(): Flow<List<TerritoryEntity>>

    @Query("SELECT * FROM territories WHERE syncStatus = 'PENDING'")
    suspend fun pendingSync(): List<TerritoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TerritoryEntity)

    @Query("UPDATE territories SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM territories WHERE id = :id")
    suspend fun delete(id: String)
}
