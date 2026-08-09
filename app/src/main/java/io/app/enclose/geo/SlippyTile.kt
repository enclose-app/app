package io.app.enclose.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * One tile of the standard web-mercator pyramid — the addressing scheme every
 * `{z}/{x}/{y}` tile URL uses, including the basemap this app already draws.
 *
 * [x] runs west to east and [y] runs **north to south**, which is the one thing
 * about this scheme that catches people out: a tile's `y + 1` neighbour is the
 * one below it on screen, and latitude therefore decreases as `y` grows.
 */
data class Tile(val z: Int, val x: Int, val y: Int)

/**
 * Web-mercator tile arithmetic: which tiles cover a piece of the world, and
 * where a point inside a tile actually is.
 *
 * Pure maths with no Android and no map library, so the route planner's tile
 * fetching can be reasoned about (and unit tested) without a device. MapLibre
 * knows all of this too, but it only ever tells the *map* — a planner that needs
 * to fetch a few tiles of road data has no map to ask.
 */
object SlippyTile {

    /** Latitude past which web mercator stops being defined. */
    const val MAX_LATITUDE = 85.05112878

    /** The tile containing [point] at [zoom]. */
    fun of(point: LatLng, zoom: Int): Tile {
        val n = 1 shl zoom
        val x = floor(xWorld(point.lng) * n).toInt().coerceIn(0, n - 1)
        val y = floor(yWorld(point.lat) * n).toInt().coerceIn(0, n - 1)
        return Tile(zoom, x, y)
    }

    /**
     * Every tile at [zoom] that overlaps [bounds], row by row.
     *
     * Deliberately not clamped in x to the world's edges by wrapping: a walk
     * planned across the antimeridian is not a case this app has, and silently
     * fetching the other side of the planet would be a stranger failure than a
     * search area that stops at the edge.
     */
    fun cover(bounds: GeoBounds, zoom: Int): List<Tile> {
        val n = 1 shl zoom
        val minX = floor(xWorld(bounds.west) * n).toInt().coerceIn(0, n - 1)
        val maxX = floor(xWorld(bounds.east) * n).toInt().coerceIn(0, n - 1)
        // North is the *smaller* y, so the range runs from the top edge down.
        val minY = floor(yWorld(bounds.north) * n).toInt().coerceIn(0, n - 1)
        val maxY = floor(yWorld(bounds.south) * n).toInt().coerceIn(0, n - 1)
        val tiles = ArrayList<Tile>((maxX - minX + 1) * (maxY - minY + 1))
        for (y in minY..maxY) {
            for (x in minX..maxX) tiles.add(Tile(zoom, x, y))
        }
        return tiles
    }

    /**
     * Where a point inside [tile] is, given tile-local coordinates on a grid of
     * [extent] units per side (4096 in every vector tile in practice).
     *
     * Coordinates outside `0..extent` are allowed and meaningful: vector tiles
     * carry a margin of geometry past their own edges so lines can be drawn
     * without a seam.
     */
    fun toLatLng(tile: Tile, x: Double, y: Double, extent: Int): LatLng {
        val n = (1 shl tile.z).toDouble()
        val worldX = (tile.x + x / extent) / n
        val worldY = (tile.y + y / extent) / n
        return LatLng(latOf(worldY), worldX * 360.0 - 180.0)
    }

    /**
     * How wide one tile is on the ground at this zoom and latitude, in metres.
     * The planner uses it to keep the number of tiles it fetches in proportion
     * to the area it actually needs.
     */
    fun tileSpanMeters(zoom: Int, latitude: Double): Double =
        EQUATOR_METERS * cos(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))) /
            (1 shl zoom)

    private fun xWorld(lng: Double): Double = (lng + 180.0) / 360.0

    private fun yWorld(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = Math.toRadians(clamped)
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
    }

    private fun latOf(worldY: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))

    private const val EQUATOR_METERS = 40_075_016.686
}

/**
 * A north-up rectangle of the world, as a search area rather than as geometry —
 * [GeoPolygon] is what the app draws and claims with, and mixing the two would
 * put a rectangle where a walked ring belongs.
 */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    companion object {
        /**
         * The square of side `2 * [radiusMeters]` centred on [center].
         *
         * The longitude half-width is divided by cos(latitude) because a degree
         * of longitude shrinks towards the poles; without it the box is too
         * narrow everywhere but the equator, and the planner would fetch too
         * little road to find a loop in — at 60° north, half of what it asked
         * for.
         */
        fun around(center: LatLng, radiusMeters: Double): GeoBounds {
            val latSpan = Math.toDegrees(radiusMeters / EARTH_RADIUS_M)
            val cosLat = cos(Math.toRadians(center.lat)).let { if (abs(it) < 1e-6) 1e-6 else it }
            val lngSpan = latSpan / cosLat
            return GeoBounds(
                south = (center.lat - latSpan).coerceIn(-SlippyTile.MAX_LATITUDE, SlippyTile.MAX_LATITUDE),
                west = (center.lng - lngSpan).coerceIn(-180.0, 180.0),
                north = (center.lat + latSpan).coerceIn(-SlippyTile.MAX_LATITUDE, SlippyTile.MAX_LATITUDE),
                east = (center.lng + lngSpan).coerceIn(-180.0, 180.0),
            )
        }

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
