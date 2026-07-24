package io.app.enclose.geo

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import kotlin.math.cos

/**
 * Polygon boolean geometry for claim overlaps, backed by JTS.
 *
 * WGS84 coordinates are projected to a local planar frame (equirectangular
 * around a reference latitude) so JTS — which is planar — can operate, then
 * unprojected back. Accurate at city-walk scale, matching [Geo]'s area math.
 */
object GeoClip {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private val factory = GeometryFactory()
    private const val OVERLAP_EPSILON_SQ_M = 1.0

    /** True if [cut] overlaps [base] by more than a negligible sliver. */
    fun overlaps(base: List<GeoPolygon>, cut: GeoRing): Boolean {
        if (base.isEmpty() || cut.size < 3) return false
        val lat0 = cut.map { it.lat }.average()
        val b = toJts(base, lat0) ?: return false
        val c = toJts(listOf(listOf(cut)), lat0) ?: return false
        return runCatching { b.intersection(c).area > OVERLAP_EPSILON_SQ_M }.getOrDefault(false)
    }

    /**
     * [base] with [cut] removed, as a list of polygons (may be empty if [cut]
     * fully covers [base], or several polygons if it splits it). On any failure
     * the original [base] is returned unchanged.
     */
    fun subtract(base: List<GeoPolygon>, cut: GeoRing): List<GeoPolygon> {
        if (base.isEmpty() || cut.size < 3) return base
        val lat0 = cut.map { it.lat }.average()
        val b = toJts(base, lat0) ?: return base
        val c = toJts(listOf(listOf(cut)), lat0) ?: return base
        val diff = runCatching { b.difference(c) }.getOrNull() ?: return base
        return fromJts(diff, lat0)
    }

    // --- projection ----------------------------------------------------------

    private fun project(p: LatLng, lat0: Double): Coordinate = Coordinate(
        EARTH_RADIUS_M * Math.toRadians(p.lng) * cos(Math.toRadians(lat0)),
        EARTH_RADIUS_M * Math.toRadians(p.lat),
    )

    private fun unproject(x: Double, y: Double, lat0: Double): LatLng {
        val lat = Math.toDegrees(y / EARTH_RADIUS_M)
        val lng = Math.toDegrees(x / (EARTH_RADIUS_M * cos(Math.toRadians(lat0))))
        return LatLng(lat, lng)
    }

    // --- conversion ----------------------------------------------------------

    private fun ringToJts(ring: GeoRing, lat0: Double): LinearRing? {
        if (ring.size < 3) return null
        val coords = ring.map { project(it, lat0) }.toMutableList()
        if (coords.first() != coords.last()) coords.add(coords.first())
        return runCatching { factory.createLinearRing(coords.toTypedArray()) }.getOrNull()
    }

    private fun polygonToJts(poly: GeoPolygon, lat0: Double): Polygon? {
        val shell = ringToJts(poly.firstOrNull() ?: return null, lat0) ?: return null
        val holes = poly.drop(1).mapNotNull { ringToJts(it, lat0) }.toTypedArray()
        return runCatching { factory.createPolygon(shell, holes) }.getOrNull()
    }

    private fun toJts(polygons: List<GeoPolygon>, lat0: Double): Geometry? {
        val jts = polygons.mapNotNull { polygonToJts(it, lat0) }
        if (jts.isEmpty()) return null
        val geom = if (jts.size == 1) jts[0] else factory.createMultiPolygon(jts.toTypedArray())
        // buffer(0) repairs orientation / minor self-intersections from GPS noise.
        return runCatching { geom.buffer(0.0) }.getOrDefault(geom)
    }

    private fun fromJts(geometry: Geometry, lat0: Double): List<GeoPolygon> {
        val out = mutableListOf<GeoPolygon>()
        for (i in 0 until geometry.numGeometries) {
            val g = geometry.getGeometryN(i)
            if (g is Polygon && !g.isEmpty) out.add(jtsPolygonToGeo(g, lat0))
        }
        return out
    }

    private fun jtsPolygonToGeo(polygon: Polygon, lat0: Double): GeoPolygon {
        val rings = mutableListOf<GeoRing>()
        rings.add(coordsToRing(polygon.exteriorRing.coordinates, lat0))
        for (i in 0 until polygon.numInteriorRing) {
            rings.add(coordsToRing(polygon.getInteriorRingN(i).coordinates, lat0))
        }
        return rings
    }

    private fun coordsToRing(coords: Array<Coordinate>, lat0: Double): GeoRing {
        val ring = coords.map { unproject(it.x, it.y, lat0) }
        // Drop the JTS closing duplicate; our rings are implicitly closed.
        return if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
    }
}
