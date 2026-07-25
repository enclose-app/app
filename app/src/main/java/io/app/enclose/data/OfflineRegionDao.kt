package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineRegionDao {

    @Query("SELECT * FROM offline_regions")
    suspend fun all(): List<OfflineRegionEntity>

    @Query("SELECT * FROM offline_regions ORDER BY visitCount DESC")
    fun observeAll(): Flow<List<OfflineRegionEntity>>

    @Query("SELECT * FROM offline_regions WHERE city = :city")
    suspend fun byCity(city: String): OfflineRegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(region: OfflineRegionEntity)

    @Query(
        "UPDATE offline_regions SET sizeBytes = :sizeBytes, " +
            "completedAtEpochMs = :completedAtEpochMs WHERE city = :city",
    )
    suspend fun updateProgress(city: String, sizeBytes: Long, completedAtEpochMs: Long?)

    /**
     * Counting a visit must not touch [OfflineRegionEntity.sizeBytes]: this runs
     * from the map as the camera settles, and a whole-row write would race the
     * download reporting its size.
     */
    @Query(
        "UPDATE offline_regions SET visitCount = visitCount + 1, " +
            "lastVisitedAtEpochMs = :atEpochMs WHERE city = :city",
    )
    suspend fun recordVisit(city: String, atEpochMs: Long)

    @Query("DELETE FROM offline_regions WHERE city = :city")
    suspend fun delete(city: String)
}
