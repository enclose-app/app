package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkDao {

    @Query("SELECT * FROM walks ORDER BY closedAtEpochMs DESC")
    fun observeAll(): Flow<List<WalkEntity>>

    @Query("SELECT * FROM walks WHERE syncStatus = 'PENDING'")
    suspend fun pendingSync(): List<WalkEntity>

    /** Every walk ever closed, claimed or not, for a backup. */
    @Query("SELECT * FROM walks ORDER BY closedAtEpochMs ASC")
    suspend fun all(): List<WalkEntity>

    /** Ids only, so a restore can report what it added versus replaced. */
    @Query("SELECT id FROM walks")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WalkEntity>)

    /**
     * Record a freshly closed loop. IGNORE so it can't clobber a row the claim
     * flow may have already written as claimed (order-independent, race-safe).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: WalkEntity)

    /** Insert or replace — used when the walk is claimed. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WalkEntity)

    @Query("UPDATE walks SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
