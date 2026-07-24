package io.app.enclose.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TerritoryEntity::class, WalkEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class EncloseDatabase : RoomDatabase() {

    abstract fun territoryDao(): TerritoryDao

    abstract fun walkDao(): WalkDao

    companion object {
        @Volatile
        private var instance: EncloseDatabase? = null

        fun get(context: Context): EncloseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EncloseDatabase::class.java,
                    "enclose.db",
                )
                    // Pre-release: recreate the DB on schema change instead of
                    // writing migrations. Replace with real migrations before ship.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
