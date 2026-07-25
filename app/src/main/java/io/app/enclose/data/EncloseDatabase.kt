package io.app.enclose.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TerritoryEntity::class,
        WalkEntity::class,
        ProfileEntity::class,
        WalkProgressEntity::class,
        WalkProgressPointEntity::class,
    ],
    version = 8,
    // Exported to app/schemas so every future migration can be written against
    // the real previous schema and verified, instead of guessed at.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EncloseDatabase : RoomDatabase() {

    abstract fun territoryDao(): TerritoryDao

    abstract fun walkDao(): WalkDao

    abstract fun profileDao(): ProfileDao

    abstract fun walkProgressDao(): WalkProgressDao

    companion object {
        @Volatile
        private var instance: EncloseDatabase? = null

        /**
         * Adds the reverse-geocoded city to territories. It backfills as blank,
         * and the resolver fills it in.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE territories ADD COLUMN city TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Lets a conquered territory be archived instead of deleted. Both
         * columns are nullable, so every existing claim reads as still standing,
         * which is exactly what it is.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE territories ADD COLUMN conqueredAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE territories ADD COLUMN conqueredById TEXT")
            }
        }

        /**
         * Adds storage for the walk in progress. Both statements are copied
         * verbatim from the exported `8.json` — Room compares the live schema
         * against that file, so hand-written CREATE TABLE has to match it to the
         * character.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `walk_progress` " +
                        "(`id` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, " +
                        "`activityType` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `walk_progress_points` " +
                        "(`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL)",
                )
            }
        }

        /**
         * There is deliberately **no** destructive-migration fallback here.
         *
         * A territory is a walk someone actually went out and did; it cannot be
         * re-entered from the couch the way a note or a setting can. Dropping
         * the tables to make a schema change land is therefore never an
         * acceptable trade, not even pre-release.
         *
         * The consequence is that every version bump MUST ship a [Migration]
         * covering it. Without one, Room throws when the database is opened
         * rather than quietly emptying it — a loud failure in development, in
         * exchange for user data that cannot silently disappear in the field.
         */
        fun get(context: Context): EncloseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EncloseDatabase::class.java,
                    "enclose.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build().also { instance = it }
            }
    }
}
