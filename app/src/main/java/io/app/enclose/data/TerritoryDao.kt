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

    /**
     * Every row, standing and fallen alike, for a backup.
     *
     * Deliberately not [observeActive] with the conquered ones added: a fallen
     * claim keeps the geometry it held when it fell and is shown in the profile's
     * history, so a backup that dropped it would lose walking that happened.
     */
    @Query("SELECT * FROM territories ORDER BY claimedAtEpochMs ASC")
    abstract suspend fun all(): List<TerritoryEntity>

    /** Ids only, so a restore can report what it added versus replaced. */
    @Query("SELECT id FROM territories")
    abstract suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<TerritoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: TerritoryEntity)

    @Query("UPDATE territories SET syncStatus = 'SYNCED' WHERE id IN (:ids)")
    abstract suspend fun markSynced(ids: List<String>)

    /**
     * Claims still waiting on reverse geocoding, oldest first. Catches a missing
     * country as well as a missing city, so claims placed before countries were
     * stored get picked up by the same backfill rather than needing their own.
     */
    @Query(
        "SELECT * FROM territories WHERE city = '' OR country = '' " +
            "ORDER BY claimedAtEpochMs ASC",
    )
    abstract suspend fun withoutPlace(): List<TerritoryEntity>

    /**
     * Deliberately leaves syncStatus alone: the place is derived locally from
     * coordinates the backend already has, so resolving it is not an edit the
     * user made and shouldn't queue an upload.
     */
    @Query("UPDATE territories SET city = :city, country = :country WHERE id = :id")
    abstract suspend fun updatePlace(id: String, city: String, country: String)

    /**
     * Claims that have never been offered to the route matcher, oldest first.
     *
     * Keyed on `snappedAtEpochMs IS NULL` rather than on a blank ring, because a
     * blank ring is also what a *refused* match leaves behind. A loop round a
     * park has no roads to match onto and will be refused every time, so keying
     * on the geometry would re-upload the same unmatchable walks on every
     * backfill, forever.
     *
     * Conquered claims are excluded — unlike [withoutPlace], which deliberately
     * includes them so a fallen claim still gets a name for the history list.
     * There is no reason to spend a network round trip on geometry that will
     * never be drawn.
     */
    @Query(
        "SELECT * FROM territories WHERE snappedAtEpochMs IS NULL " +
            "AND conqueredAtEpochMs IS NULL ORDER BY claimedAtEpochMs ASC",
    )
    abstract suspend fun withoutSnap(): List<TerritoryEntity>

    /** How many claims a backfill would upload, for the UI to say so up front. */
    @Query(
        "SELECT COUNT(*) FROM territories WHERE snappedAtEpochMs IS NULL " +
            "AND conqueredAtEpochMs IS NULL",
    )
    abstract suspend fun withoutSnapCount(): Int

    /**
     * Record the outcome of a match attempt — a blank [json] for a refusal, which
     * is why the timestamp is always written.
     *
     * A targeted update rather than [upsert] for the same reason [updatePlace] is
     * one, and it leaves syncStatus alone for the same reason too: the snapped
     * outline is derived from coordinates a backend would already have, so it is
     * not an edit the user made and mustn't queue an upload.
     */
    @Query("UPDATE territories SET snappedJson = :json, snappedAtEpochMs = :at WHERE id = :id")
    abstract suspend fun updateSnap(id: String, json: String, at: Long)

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
