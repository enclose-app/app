package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.app.enclose.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "walks")
data class WalkEntity(
    @PrimaryKey val id: String,
    /** Closed ring, JSON of [{lat,lng}, ...]. */
    val ringJson: String,
    val areaSqMeters: Double,
    val perimeterMeters: Double,
    val distanceToStartMeters: Double,
    val closedAtEpochMs: Long,
    /** Null for walks recorded before the start time was kept. */
    val startedAtEpochMs: Long? = null,
    val elevationGainMeters: Double = 0.0,
    /** Null for walks recorded before moving time was measured. */
    val movingMs: Long? = null,
    val claimed: Boolean,
    val syncStatus: SyncStatus,
) {
    fun toDomain(): Walk = Walk(
        id = id,
        ring = ringFromJson(ringJson),
        areaSqMeters = areaSqMeters,
        perimeterMeters = perimeterMeters,
        distanceToStartMeters = distanceToStartMeters,
        closedAtEpochMs = closedAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        elevationGainMeters = elevationGainMeters,
        movingMs = movingMs,
        claimed = claimed,
        syncStatus = syncStatus,
    )

    companion object {
        fun fromDomain(w: Walk): WalkEntity = WalkEntity(
            id = w.id,
            ringJson = ringToJson(w.ring),
            areaSqMeters = w.areaSqMeters,
            perimeterMeters = w.perimeterMeters,
            distanceToStartMeters = w.distanceToStartMeters,
            closedAtEpochMs = w.closedAtEpochMs,
            startedAtEpochMs = w.startedAtEpochMs,
            elevationGainMeters = w.elevationGainMeters,
            movingMs = w.movingMs,
            claimed = w.claimed,
            syncStatus = w.syncStatus,
        )

        private fun ringToJson(ring: List<LatLng>): String {
            val arr = JSONArray()
            ring.forEach { arr.put(JSONObject().put("lat", it.lat).put("lng", it.lng)) }
            return arr.toString()
        }

        private fun ringFromJson(json: String): List<LatLng> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LatLng(o.getDouble("lat"), o.getDouble("lng"))
            }
        }
    }
}
