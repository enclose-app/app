package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class WalkProgressDao {

    @Query("SELECT * FROM walk_progress WHERE id = '${WalkProgressEntity.SINGLETON_ID}'")
    abstract suspend fun session(): WalkProgressEntity?

    @Query("SELECT * FROM walk_progress_points ORDER BY seq ASC")
    abstract suspend fun points(): List<WalkProgressPointEntity>

    @Insert
    abstract suspend fun insertPoints(points: List<WalkProgressPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSession(session: WalkProgressEntity)

    @Query("DELETE FROM walk_progress")
    abstract suspend fun deleteSession()

    @Query("DELETE FROM walk_progress_points")
    abstract suspend fun deletePoints()

    /**
     * Open a new session, discarding anything left behind. A leftover session
     * means the last walk's process died without cleaning up, so starting a new
     * walk is the natural moment to clear it.
     */
    @Transaction
    open suspend fun begin(session: WalkProgressEntity) {
        clear()
        upsertSession(session)
    }

    @Transaction
    open suspend fun clear() {
        deleteSession()
        deletePoints()
    }
}
