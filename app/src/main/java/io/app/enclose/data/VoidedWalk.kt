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
 * A walk that ended without a claim — kept, not thrown away.
 *
 * Two kinds live here. Walks stopped without a claim: Stop pressed away from the
 * start and Save chosen, or a loop with too much of its boundary bridged over
 * stretches nothing vouched for. And walks the old anti-cheat ended, from before
 * it stopped ending walks at all; they have no times or steps, so the new rules
 * have nothing to judge them by, and they stay as they were.
 *
 * Either way this is a record, never a claim, and is held apart from [Walk]
 * (which every claim, sync and passport path reads as a closed loop) on purpose.
 * "Nothing the user walked for is ever destroyed" (see `CLAUDE.md`) applies to
 * these as much as to a claim.
 */
data class VoidedWalk(
    val id: String,
    /** The recorded path, in walked order. */
    val path: List<LatLng>,
    /** First fix; null when the walk ended before one landed. */
    val startedAtEpochMs: Long?,
    /** When it ended. Named for the voids it was first made for. */
    val voidedAtEpochMs: Long,
    val distanceMeters: Double,
    val reason: Reason,
    /** Walked on foot, and bridged; null for walks from before stretches were judged. */
    val onFootMeters: Double? = null,
    val bridgedMeters: Double? = null,
    /** Steps counted, per source; null where none were. */
    val phoneSteps: Int? = null,
    val watchSteps: Int? = null,
) {
    /**
     * Why it was kept without a claim. The last two mirror
     * `tracking.UnclaimedReason` by name rather than importing it: `tracking`
     * depends on `data`, never the reverse. The first three are the old
     * anti-cheat's, kept so the walks it ended still say why.
     */
    enum class Reason {
        VEHICLE,
        TOO_FAST,
        UNVERIFIED_GAP,
        NOT_CLOSED,
        TOO_MUCH_BRIDGED,
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
    val onFootMeters: Double? = null,
    val bridgedMeters: Double? = null,
    val phoneSteps: Int? = null,
    val watchSteps: Int? = null,
) {
    fun toDomain(): VoidedWalk = VoidedWalk(
        id = id,
        path = pathFromJson(pathJson),
        startedAtEpochMs = startedAtEpochMs,
        voidedAtEpochMs = voidedAtEpochMs,
        distanceMeters = distanceMeters,
        reason = VoidedWalk.Reason.of(reason),
        onFootMeters = onFootMeters,
        bridgedMeters = bridgedMeters,
        phoneSteps = phoneSteps,
        watchSteps = watchSteps,
    )

    companion object {
        fun fromDomain(w: VoidedWalk): VoidedWalkEntity = VoidedWalkEntity(
            id = w.id,
            pathJson = pathToJson(w.path),
            startedAtEpochMs = w.startedAtEpochMs,
            voidedAtEpochMs = w.voidedAtEpochMs,
            distanceMeters = w.distanceMeters,
            reason = w.reason.name,
            onFootMeters = w.onFootMeters,
            bridgedMeters = w.bridgedMeters,
            phoneSteps = w.phoneSteps,
            watchSteps = w.watchSteps,
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
