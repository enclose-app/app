package io.app.enclose.geo

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.cos

/**
 * Polygon geometry over claims, backed by JTS: how much ground they share, and
 * whether a ring is simple.
 *
 * WGS84 coordinates are projected to a local planar frame (equirectangular
 * around a reference latitude) so JTS — which is planar — can operate.
 * Accurate at city-walk scale, matching [Geo]'s area math.
 */
object GeoClip {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private val factory = GeometryFactory()

    /**
     * How much ground [shapes] share, in square metres: the sum of their areas
     * less the area of their union, both measured in one projection around the
     * shapes' mean latitude. Zero when none of them overlap.
     *
     * Zero as well on any failure. The caller takes this off a sum of areas, so
     * a failure reads as the old figure — every claim counted whole — rather
     * than as ground lost.
     */
    fun sharedSqMeters(shapes: List<List<GeoPolygon>>): Double {
        val points = shapes.flatten().flatten().flatten()
        if (shapes.size < 2 || points.isEmpty()) return 0.0
        val lat0 = points.map { it.lat }.average()
        val geometries = shapes.mapNotNull { toJts(it, lat0) }
        if (geometries.size < 2) return 0.0
        return runCatching {
            val union = UnaryUnionOp.union(geometries) ?: return 0.0
            (geometries.sumOf { it.area } - union.area).coerceAtLeast(0.0)
        }.getOrDefault(0.0)
    }

    /**
     * True when [ring] encloses an area without crossing itself.
     *
     * For checking geometry that came from somewhere else — a map matcher hands
     * back a *path*, and a path fed a closed loop fails at the seam where the end
     * meets the start more than anywhere else: it doubles back, or crosses. A
     * figure-of-eight has an area and a perimeter that both look reasonable, so
     * nothing but a topology check catches it, and JTS will happily compute a
     * nonsense difference against one later.
     *
     * Answers false rather than throwing on anything it can't build, matching the
     * rest of this object: unusable input is not valid input.
     */
    fun isSimpleRing(ring: GeoRing): Boolean {
        if (ring.size < 3) return false
        val lat0 = ring.map { it.lat }.average()
        val polygon = polygonToJts(listOf(ring), lat0) ?: return false
        return runCatching { polygon.isValid && !polygon.isEmpty }.getOrDefault(false)
    }

    // --- projection ----------------------------------------------------------

    private fun project(p: LatLng, lat0: Double): Coordinate = Coordinate(
        EARTH_RADIUS_M * Math.toRadians(p.lng) * cos(Math.toRadians(lat0)),
        EARTH_RADIUS_M * Math.toRadians(p.lat),
    )

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
}
