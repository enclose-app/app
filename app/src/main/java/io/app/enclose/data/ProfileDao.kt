package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile WHERE id = :id LIMIT 1")
    fun observe(id: String = ProfileEntity.SINGLETON_ID): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = :id LIMIT 1")
    suspend fun get(id: String = ProfileEntity.SINGLETON_ID): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileEntity)

    @Query("UPDATE profile SET firstName = :first, lastName = :last WHERE id = :id")
    suspend fun updateName(
        first: String,
        last: String,
        id: String = ProfileEntity.SINGLETON_ID,
    )
}
