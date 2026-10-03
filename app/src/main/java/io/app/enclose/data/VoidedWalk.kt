package io.app.enclose.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.app.enclose.geo.LatLng
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * A walk the anti-cheat ended — kept, not thrown away.
 *
 * The void itself stands: a walk that read as a drive must not claim anything,
 * so this is a record, never a claim, and is held apart from [Walk] (which every
 * claim, sync and passport path reads as a closed loop) on purpose. What changed
 * is that the ground covered is no longer erased with it. Most voids are honest
 * walks the classifier got wrong, and "Nothing the user walked for is ever
 * destroyed" (see `CLAUDE.md`) applies to them as much as to a claim.
 */
data class VoidedWalk(
    val id: String,
    /** The recorded path, in walked order. */
    val path: List<LatLng>,
    /** First fix; null when the walk was voided before one landed. */
    val startedAtEpochMs: Long?,
    val voidedAtEpochMs: Long,
    val distanceMeters: Double,
    val reason: Reason,
) {
    /**
     * Why it was voided. Mirrors `tracking.VoidReason` by name rather than
     * importing it: `tracking` depends on `data`, never the reverse.
     */
    enum class Reason {
        VEHICLE,
        TOO_FAST,
        UNVERIFIED_GAP,
        ;

        companion object {
            /** An unknown name (a backup from a newer build) reads as the commonest reason. */
            fun of(name: String): Reason = entries.firstOrNull { it.name == name } ?: VEHICLE
        }
    }
}

@Entity(tableName = "voided_walks")
data class VoidedWalkEntity(
    @PrimaryKey val id: String,
    /** Path, JSON of [{lat,lng}, ...] — the same shape as `walks.ringJson`. */
    val pathJson: String,
    val startedAtEpochMs: Long?,
    val voidedAtEpochMs: Long,
    val distanceMeters: Double,
    /** A [VoidedWalk.Reason] name. Text, so a reason added later still reads back. */
    val reason: String,
) {
    fun toDomain(): VoidedWalk = VoidedWalk(
        id = id,
        path = pathFromJson(pathJson),
        startedAtEpochMs = startedAtEpochMs,
        voidedAtEpochMs = voidedAtEpochMs,
        distanceMeters = distanceMeters,
        reason = VoidedWalk.Reason.of(reason),
    )

    companion object {
        fun fromDomain(w: VoidedWalk): VoidedWalkEntity = VoidedWalkEntity(
            id = w.id,
            pathJson = pathToJson(w.path),
            startedAtEpochMs = w.startedAtEpochMs,
            voidedAtEpochMs = w.voidedAtEpochMs,
            distanceMeters = w.distanceMeters,
            reason = w.reason.name,
        )

        private fun pathToJson(path: List<LatLng>): String {
            val arr = JSONArray()
            path.forEach { arr.put(JSONObject().put("lat", it.lat).put("lng", it.lng)) }
            return arr.toString()
        }

        private fun pathFromJson(json: String): List<LatLng> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LatLng(o.getDouble("lat"), o.getDouble("lng"))
            }
        }
    }
}

@Dao
interface VoidedWalkDao {

    @Query("SELECT * FROM voided_walks ORDER BY voidedAtEpochMs DESC")
    fun observeAll(): Flow<List<VoidedWalkEntity>>

    /** Every one, for a backup. */
    @Query("SELECT * FROM voided_walks ORDER BY voidedAtEpochMs ASC")
    suspend fun all(): List<VoidedWalkEntity>

    /** Ids only, so a restore can report what it added versus replaced. */
    @Query("SELECT id FROM voided_walks")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<VoidedWalkEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: VoidedWalkEntity)

    @Query("DELETE FROM voided_walks WHERE id = :id")
    suspend fun delete(id: String)
}

class VoidedWalkRepository(private val dao: VoidedWalkDao) {

    /** Most recent first. */
    val voidedWalks: Flow<List<VoidedWalk>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun save(walk: VoidedWalk) = dao.insertIfAbsent(VoidedWalkEntity.fromDomain(walk))

    /** Only ever on the user's say-so, from the list that shows them. */
    suspend fun delete(id: String) = dao.delete(id)
}
