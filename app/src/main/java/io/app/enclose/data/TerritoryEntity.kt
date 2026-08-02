package io.app.enclose.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.app.enclose.geo.GeoPolygon
import io.app.enclose.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "territories")
data class TerritoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Original walked ring, JSON of [{lat,lng}, ...]. */
    val ringJson: String,
    /** Effective claimed geometry: JSON of [ [ [ {lat,lng} ] ] ] (multipolygon). */
    val geometryJson: String,
    val areaSqMeters: Double,
    val perimeterMeters: Double,
    val claimedAtEpochMs: Long,
    val colorHex: String,
    /** Free-form user notes about this territory. */
    val notes: String = "",
    /** Reverse-geocoded city, or blank until it resolves. */
    val city: String = "",
    /** Reverse-geocoded country, or blank until it resolves. */
    val country: String = "",
    /** Set when a later claim swallowed this one whole; null while it stands. */
    val conqueredAtEpochMs: Long? = null,
    /** The territory that took this one. */
    val conqueredById: String? = null,
    /** Road-matched boundary, JSON of [{lat,lng}, ...]. Blank until matched. */
    val snappedJson: String = "",
    /** When matching was last attempted; null means never. */
    val snappedAtEpochMs: Long? = null,
    /** When a later claim last carved this one; null while it stands as walked. */
    val carvedAtEpochMs: Long? = null,
    val syncStatus: SyncStatus,
) {
    fun toDomain(): Territory = Territory(
        id = id,
        name = name,
        ring = ringFromJson(ringJson),
        polygons = polygonsFromJson(geometryJson),
        areaSqMeters = areaSqMeters,
        perimeterMeters = perimeterMeters,
        claimedAtEpochMs = claimedAtEpochMs,
        // The brand switched from green to purple; green is no longer selectable,
        // so any stored legacy-green value is an old auto-default — show it purple.
        colorHex = if (colorHex == LEGACY_GREEN) Territory.DEFAULT_COLOR else colorHex,
        notes = notes,
        city = city,
        country = country,
        conqueredAtEpochMs = conqueredAtEpochMs,
        conqueredById = conqueredById,
        // Guarded, not passed straight in: `JSONArray("")` throws, and every row
        // written before this column existed holds "". Without this, the first
        // read after upgrading would throw inside a Flow collector — for every
        // user, on every territory they have ever walked.
        snappedRing = if (snappedJson.isBlank()) emptyList() else ringFromJson(snappedJson),
        snappedAtEpochMs = snappedAtEpochMs,
        carvedAtEpochMs = carvedAtEpochMs,
        syncStatus = syncStatus,
    )

    companion object {
        /** The old default territory color, before the purple rebrand. */
        private const val LEGACY_GREEN = "#2E7D4F"

        fun fromDomain(t: Territory): TerritoryEntity = TerritoryEntity(
            id = t.id,
            name = t.name,
            ringJson = ringToJson(t.ring),
            geometryJson = polygonsToJson(t.polygons),
            areaSqMeters = t.areaSqMeters,
            perimeterMeters = t.perimeterMeters,
            claimedAtEpochMs = t.claimedAtEpochMs,
            colorHex = t.colorHex,
            notes = t.notes,
            city = t.city,
            country = t.country,
            conqueredAtEpochMs = t.conqueredAtEpochMs,
            conqueredById = t.conqueredById,
            // Carried through here as well as in toDomain(), and both matter:
            // `upsert` is a whole-row REPLACE, so a field missing from this
            // mapping is silently reset by the next rename, recolor or notes
            // edit — with no compile error to catch it.
            snappedJson = if (t.snappedRing.isEmpty()) "" else ringToJson(t.snappedRing),
            snappedAtEpochMs = t.snappedAtEpochMs,
            carvedAtEpochMs = t.carvedAtEpochMs,
            syncStatus = t.syncStatus,
        )

        fun ringToJson(ring: List<LatLng>): String {
            val arr = JSONArray()
            ring.forEach { arr.put(pointJson(it)) }
            return arr.toString()
        }

        fun ringFromJson(json: String): List<LatLng> = ringFromArray(JSONArray(json))

        private fun polygonsToJson(polygons: List<GeoPolygon>): String {
            val polygonsArr = JSONArray()
            polygons.forEach { poly ->
                val ringsArr = JSONArray()
                poly.forEach { ring ->
                    val ringArr = JSONArray()
                    ring.forEach { ringArr.put(pointJson(it)) }
                    ringsArr.put(ringArr)
                }
                polygonsArr.put(ringsArr)
            }
            return polygonsArr.toString()
        }

        private fun polygonsFromJson(json: String): List<GeoPolygon> {
            val polygonsArr = JSONArray(json)
            return (0 until polygonsArr.length()).map { p ->
                val ringsArr = polygonsArr.getJSONArray(p)
                (0 until ringsArr.length()).map { r -> ringFromArray(ringsArr.getJSONArray(r)) }
            }
        }

        private fun ringFromArray(arr: JSONArray): List<LatLng> =
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LatLng(o.getDouble("lat"), o.getDouble("lng"))
            }

        private fun pointJson(p: LatLng): JSONObject =
            JSONObject().put("lat", p.lat).put("lng", p.lng)
    }
}

/** Persists the [SyncStatus] enum as its name. */
class Converters {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
