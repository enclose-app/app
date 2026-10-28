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
    /** Null for walks recorded before stretches were judged. */
    val onFootMeters: Double? = null,
    val bridgedMeters: Double? = null,
    val phoneSteps: Int? = null,
    val watchSteps: Int? = null,
    /** The full recorded track, in the same shape as [ringJson]; null when it is the ring. */
    val trackJson: String? = null,
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
        onFootMeters = onFootMeters,
        bridgedMeters = bridgedMeters,
        phoneSteps = phoneSteps,
        watchSteps = watchSteps,
        // A track that can't be read is no reason to lose the walk it belongs to.
        track = trackJson?.let { runCatching { ringFromJson(it) }.getOrNull() },
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
            onFootMeters = w.onFootMeters,
            bridgedMeters = w.bridgedMeters,
            phoneSteps = w.phoneSteps,
            watchSteps = w.watchSteps,
            trackJson = w.track?.let(::ringToJson),
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
