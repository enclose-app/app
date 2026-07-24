package io.app.enclose.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A single WGS84 coordinate. Kept independent of any map library. */
data class LatLng(val lat: Double, val lng: Double)

/** A polygon ring (ordered, implicitly closed). */
typealias GeoRing = List<LatLng>

/** A polygon: exterior ring first, followed by any holes. */
typealias GeoPolygon = List<GeoRing>

object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance between two points, in meters (haversine). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Total length of a path (sum of consecutive segment distances), in meters. */
    fun pathLengthMeters(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Area of a closed polygon in square meters, using an equirectangular
     * projection around the polygon's mean latitude. Accurate enough at
     * city-walk scale. The ring is treated as implicitly closed.
     */
    fun polygonAreaSqMeters(ring: List<LatLng>): Double {
        if (ring.size < 3) return 0.0
        val lat0 = Math.toRadians(ring.map { it.lat }.average())
        val cosLat0 = cos(lat0)

        // Project each point to local meters (x = east, y = north).
        val xs = DoubleArray(ring.size)
        val ys = DoubleArray(ring.size)
        for (i in ring.indices) {
            xs[i] = Math.toRadians(ring[i].lng) * cosLat0 * EARTH_RADIUS_M
            ys[i] = Math.toRadians(ring[i].lat) * EARTH_RADIUS_M
        }

        // Shoelace formula.
        var sum = 0.0
        for (i in ring.indices) {
            val j = (i + 1) % ring.size
            sum += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return kotlin.math.abs(sum) / 2.0
    }

    /**
     * A closed ring approximating a circle of [radiusMeters] around [center],
     * as [segments] points on the sphere. Used to draw the loop-closing zone.
     */
    fun circlePolygon(center: LatLng, radiusMeters: Double, segments: Int = 64): List<LatLng> {
        val lat1 = Math.toRadians(center.lat)
        val lng1 = Math.toRadians(center.lng)
        val angular = radiusMeters / EARTH_RADIUS_M
        return (0 until segments).map { i ->
            val bearing = 2 * PI * i / segments
            val lat2 = kotlin.math.asin(
                sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing),
            )
            val lng2 = lng1 + atan2(
                sin(bearing) * sin(angular) * cos(lat1),
                cos(angular) - sin(lat1) * sin(lat2),
            )
            LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
        }
    }

    /** Total area of a multipolygon: each polygon's exterior minus its holes. */
    fun areaOfPolygons(polygons: List<GeoPolygon>): Double =
        polygons.sumOf { poly ->
            val exterior = poly.firstOrNull()?.let { polygonAreaSqMeters(it) } ?: 0.0
            val holes = poly.drop(1).sumOf { polygonAreaSqMeters(it) }
            (exterior - holes).coerceAtLeast(0.0)
        }

    /** Centroid of a set of points (simple average). Useful for camera focus. */
    fun centroid(points: List<LatLng>): LatLng {
        val lat = points.map { it.lat }.average()
        val lng = points.map { it.lng }.average()
        return LatLng(lat, lng)
    }

    fun radiansToDegrees(r: Double): Double = r * 180.0 / PI
}
