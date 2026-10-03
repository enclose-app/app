package io.app.enclose.export

import io.app.enclose.data.Territory
import io.app.enclose.data.VoidedWalk
import io.app.enclose.geo.GeoPolygon
import io.app.enclose.geo.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, dependency-free serializers turning a [Territory] into shareable text.
 *
 * - [toGeoJson] emits a single GeoJSON `Feature` whose geometry is a `Polygon`
 *   (one ring group) or `MultiPolygon` (several), built from the effective
 *   [Territory.polygons], with the territory's stats as `properties`.
 * - [toGpx] emits a GPX 1.1 track (`<trk>`/`<trkseg>`) of the originally walked
 *   [Territory.ring], named after the territory.
 *
 * GeoJSON coordinates are `[longitude, latitude]` per the spec (RFC 7946).
 */
object GeoExporter {

    private fun iso8601(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    /** A GeoJSON Feature string for the territory. */
    fun toGeoJson(territory: Territory): String {
        val polygons = territory.polygons.filter { it.isNotEmpty() }

        val geometry = JSONObject()
        if (polygons.size <= 1) {
            geometry.put("type", "Polygon")
            val rings = polygons.firstOrNull().orEmpty()
            geometry.put("coordinates", ringsToJson(rings))
        } else {
            geometry.put("type", "MultiPolygon")
            val multi = JSONArray()
            polygons.forEach { poly -> multi.put(ringsToJson(poly)) }
            geometry.put("coordinates", multi)
        }

        val properties = JSONObject().apply {
            put("name", territory.name)
            put("areaSqMeters", territory.areaSqMeters)
            put("perimeterMeters", territory.perimeterMeters)
            put("claimedAt", iso8601(territory.claimedAtEpochMs))
            put("colorHex", territory.colorHex)
            put("notes", territory.notes)
        }

        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", properties)
        }
        return feature.toString(2)
    }

    /** Coordinates array for one polygon: [ [ [lng,lat], ... ], ...holes ]. */
    private fun ringsToJson(rings: GeoPolygon): JSONArray {
        val out = JSONArray()
        rings.forEach { ring ->
            val ringArr = JSONArray()
            // GeoJSON linear rings must be explicitly closed (first == last).
            val closed = if (ring.isNotEmpty() && ring.first() != ring.last()) {
                ring + ring.first()
            } else {
                ring
            }
            closed.forEach { p ->
                ringArr.put(JSONArray().apply {
                    put(p.lng)
                    put(p.lat)
                })
            }
            out.put(ringArr)
        }
        return out
    }

    /** A GPX 1.1 track of the originally walked ring. */
    fun toGpx(territory: Territory): String {
        val name = xmlEscape(territory.name)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"Enclose\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
        )
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(name).append("</name>\n")
        sb.append("    <time>").append(iso8601(territory.claimedAtEpochMs)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(name).append("</name>\n")
        sb.append("    <trkseg>\n")
        // Close the loop back to the start so the track reads as a full circuit.
        val pts = closedRing(territory.ring)
        pts.forEach { p ->
            sb.append("      <trkpt lat=\"").append(p.lat)
                .append("\" lon=\"").append(p.lng).append("\"/>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * A GPX 1.1 track of a walk the anti-cheat ended. Left open — it never
     * closed a loop, and drawing it closed would invent a stretch nobody walked.
     */
    fun toGpx(walk: VoidedWalk): String {
        val name = "Enclose walk ${fileStamp(walk.startedAtEpochMs ?: walk.voidedAtEpochMs)} (didn't count)"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"Enclose\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
        )
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
        sb.append("    <time>")
            .append(iso8601(walk.startedAtEpochMs ?: walk.voidedAtEpochMs)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(xmlEscape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        walk.path.forEach { p ->
            sb.append("      <trkpt lat=\"").append(p.lat)
                .append("\" lon=\"").append(p.lng).append("\"/>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** "enclose-walk-2026-10-03-1412", dated by the walk's own start. */
    fun safeFileName(walk: VoidedWalk): String =
        "enclose-walk-${fileStamp(walk.startedAtEpochMs ?: walk.voidedAtEpochMs)}"

    private fun fileStamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(epochMs))

    private fun closedRing(ring: List<LatLng>): List<LatLng> =
        if (ring.size >= 2 && ring.first() != ring.last()) ring + ring.first() else ring

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** A filesystem-safe base filename derived from the territory name. */
    fun safeFileName(territory: Territory): String {
        val base = territory.name.trim()
            .replace(Regex("[^A-Za-z0-9-_ ]"), "")
            .replace(Regex("\\s+"), "_")
            .ifBlank { "territory" }
        return base.take(48)
    }
}
