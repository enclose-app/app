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
        OfflineRegionEntity::class,
    ],
    version = 11,
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

    abstract fun offlineRegionDao(): OfflineRegionDao

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
         * Adds climb and start time to walks, and climb to the walk in progress.
         *
         * `startedAtEpochMs` is nullable because walks recorded before this
         * genuinely have no start time — inventing one (the close time, say)
         * would show every old walk as instantaneous rather than as unknown.
         * `elevationGainMeters` is NOT NULL, so it needs a SQL default for the
         * existing rows; 0 is honest, since no altitude was ever recorded.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE walks ADD COLUMN startedAtEpochMs INTEGER")
                db.execSQL(
                    "ALTER TABLE walks ADD COLUMN elevationGainMeters REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE walk_progress " +
                        "ADD COLUMN elevationGainMeters REAL NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the country to claims (for the passport) and moving time to walks
         * (so pace excludes waiting at crossings).
         *
         * `movingMs` on `walks` is nullable while the same column on
         * `walk_progress` is not: an old walk genuinely never measured moving
         * time, and storing 0 would assert the walker never moved, whereas a
         * walk in progress always starts from a real zero.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE territories ADD COLUMN country TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE walks ADD COLUMN movingMs INTEGER")
                db.execSQL("ALTER TABLE walk_progress ADD COLUMN movingMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Tracks which cities have their map tiles cached, and how often the
         * user actually goes there — MapLibre owns the tiles but knows neither.
         * SQL copied verbatim from the exported `11.json`.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_regions` " +
                        "(`city` TEXT NOT NULL, `regionId` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `visitCount` INTEGER NOT NULL, " +
                        "`lastVisitedAtEpochMs` INTEGER NOT NULL, " +
                        "`completedAtEpochMs` INTEGER, PRIMARY KEY(`city`))",
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
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                    )
                    .build().also { instance = it }
            }
    }
}
