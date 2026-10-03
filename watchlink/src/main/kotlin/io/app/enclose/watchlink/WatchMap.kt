package io.app.enclose.watchlink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** A position, in degrees. The protocol's own, so `:wear` needs nothing of `:app`. */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * The walk as the watch's map draws it: the path so far, where it started, where
 * the walker is, and how close to the start counts as closing.
 *
 * Sent on its own Data Layer item ([WatchLink.MAP_PATH]), apart from the coarse
 * [WatchStatus], because it moves on every fix — the phone throttles it to one
 * write every few seconds — and because it is far larger.
 */
data class WatchMap(
    /** Which walk this is: the walk's first-fix time, matching [WatchStatus.startedAtEpochMs]. */
    val startedAtEpochMs: Long? = null,
    val path: List<GeoPoint> = emptyList(),
    val start: GeoPoint? = null,
    val current: GeoPoint? = null,
    val closeRadiusMeters: Double = 0.0,
) {
    fun toFields(): Map<String, Any> = buildMap {
        put(K_VERSION, WatchStatus.VERSION)
        startedAtEpochMs?.let { put(K_STARTED_AT, it) }
        put(K_LATS, path.map { encode(it.lat) }.toLongArray())
        put(K_LNGS, path.map { encode(it.lng) }.toLongArray())
        start?.let { put(K_START, longArrayOf(encode(it.lat), encode(it.lng))) }
        current?.let { put(K_CURRENT, longArrayOf(encode(it.lat), encode(it.lng))) }
        put(K_CLOSE_RADIUS, closeRadiusMeters.roundToLong())
    }

    companion object {
        /**
         * Points the phone will send. A Data Layer item holds 100 KB; this is
         * well under half of it, and with the path simplified first it covers
         * many hours of walking. See the phone's `watchMapOf`.
         */
        const val MAX_POINTS = 2_000

        fun fromFields(get: (String) -> Any?): WatchMap? {
            if (get(K_VERSION) != WatchStatus.VERSION) return null
            val lats = get(K_LATS) as? LongArray ?: return null
            val lngs = get(K_LNGS) as? LongArray ?: return null
            if (lats.size != lngs.size) return null
            return WatchMap(
                startedAtEpochMs = get(K_STARTED_AT) as? Long,
                path = lats.indices.map { GeoPoint(decode(lats[it]), decode(lngs[it])) },
                start = pointOf(get(K_START)),
                current = pointOf(get(K_CURRENT)),
                closeRadiusMeters = (get(K_CLOSE_RADIUS) as? Long ?: return null).toDouble(),
            )
        }

        private fun pointOf(value: Any?): GeoPoint? =
            (value as? LongArray)?.takeIf { it.size == 2 }?.let { GeoPoint(decode(it[0]), decode(it[1])) }

        private const val K_VERSION = "v"
        private const val K_STARTED_AT = "startedAt"
        private const val K_LATS = "lats"
        private const val K_LNGS = "lngs"
        private const val K_START = "start"
        private const val K_CURRENT = "current"
        private const val K_CLOSE_RADIUS = "closeRadius"
    }
}

/**
 * Claims near the walk, for the watch to draw under it. Sent on its own item
 * ([WatchLink.CLAIMS_PATH]) because it changes rarely — a new claim, a walk
 * starting somewhere else — and the path changes constantly.
 */
data class WatchClaims(val claims: List<Claim> = emptyList()) {

    data class Claim(
        /** ARGB, as the phone draws it. */
        val color: Int,
        /** Polygons, each an outer ring followed by any holes carved out of it. */
        val polygons: List<List<List<GeoPoint>>>,
    )

    /**
     * Flattened into parallel arrays — the Data Layer carries arrays of
     * primitives, not nested lists: per claim a colour and a polygon count, per
     * polygon a ring count, per ring a point count, then every point.
     */
    fun toFields(): Map<String, Any> {
        val polygonCounts = mutableListOf<Long>()
        val ringCounts = mutableListOf<Long>()
        val pointCounts = mutableListOf<Long>()
        val lats = mutableListOf<Long>()
        val lngs = mutableListOf<Long>()
        for (claim in claims) {
            polygonCounts += claim.polygons.size.toLong()
            for (polygon in claim.polygons) {
                ringCounts += polygon.size.toLong()
                for (ring in polygon) {
                    pointCounts += ring.size.toLong()
                    ring.forEach { lats += encode(it.lat); lngs += encode(it.lng) }
                }
            }
        }
        return mapOf(
            K_VERSION to WatchStatus.VERSION,
            K_COLORS to claims.map { it.color.toLong() }.toLongArray(),
            K_POLYGONS to polygonCounts.toLongArray(),
            K_RINGS to ringCounts.toLongArray(),
            K_POINTS to pointCounts.toLongArray(),
            K_LATS to lats.toLongArray(),
            K_LNGS to lngs.toLongArray(),
        )
    }

    companion object {
        /** Claims the phone sends: the nearest, so a long history stays in budget. */
        const val MAX_CLAIMS = 60

        fun fromFields(get: (String) -> Any?): WatchClaims? {
            if (get(K_VERSION) != WatchStatus.VERSION) return null
            val colors = get(K_COLORS) as? LongArray ?: return null
            val polygonCounts = get(K_POLYGONS) as? LongArray ?: return null
            val ringCounts = get(K_RINGS) as? LongArray ?: return null
            val pointCounts = get(K_POINTS) as? LongArray ?: return null
            val lats = get(K_LATS) as? LongArray ?: return null
            val lngs = get(K_LNGS) as? LongArray ?: return null
            // Counts that don't add up mean a corrupt item; drawing half of it
            // would put wrong shapes on the map.
            if (colors.size != polygonCounts.size ||
                polygonCounts.sum() != ringCounts.size.toLong() ||
                ringCounts.sum() != pointCounts.size.toLong() ||
                pointCounts.sum() != lats.size.toLong() ||
                lats.size != lngs.size
            ) return null

            var polygon = 0
            var ring = 0
            var point = 0
            val claims = colors.indices.map { c ->
                val polygons = (0 until polygonCounts[c].toInt()).map {
                    val rings = (0 until ringCounts[polygon++].toInt()).map {
                        (0 until pointCounts[ring++].toInt()).map {
                            GeoPoint(decode(lats[point]), decode(lngs[point])).also { point++ }
                        }
                    }
                    rings
                }
                Claim(color = colors[c].toInt(), polygons = polygons)
            }
            return WatchClaims(claims)
        }

        private const val K_VERSION = "v"
        private const val K_COLORS = "colors"
        private const val K_POLYGONS = "polygons"
        private const val K_RINGS = "rings"
        private const val K_POINTS = "points"
        private const val K_LATS = "lats"
        private const val K_LNGS = "lngs"
    }
}

/** Geometry the watch needs and the phone already has in `:app`'s `geo`. */
object WatchGeo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** A ring approximating a circle of [radiusMeters] around [center]. */
    fun circle(center: GeoPoint, radiusMeters: Double, segments: Int = 48): List<GeoPoint> {
        val dLat = radiusMeters / EARTH_RADIUS_M * 180.0 / PI
        val dLng = dLat / cos(center.lat * PI / 180.0)
        return (0..segments).map { i ->
            val a = 2 * PI * i / segments
            GeoPoint(center.lat + dLat * sin(a), center.lng + dLng * cos(a))
        }
    }
}

/** Degrees as whole 1e-7ths: about a centimetre, and exact both ways. */
private fun encode(degrees: Double): Long = (degrees * 1e7).roundToLong()
private fun decode(units: Long): Double = units / 1e7
