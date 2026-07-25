package io.app.enclose.offline

import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlin.math.cos
import kotlin.math.max

/** A rectangle of map worth keeping on the device, named after the city it covers. */
data class PlannedRegion(
    val city: String,
    val southWest: LatLng,
    val northEast: LatLng,
)

/** A region already on disk, with what it cost and how much use it gets. */
data class CachedRegion(
    val city: String,
    val sizeBytes: Long,
    val visitCount: Int,
    val lastVisitedAtEpochMs: Long,
)

/**
 * Decides what to download and what to throw away.
 *
 * The basemap streams from the network, so walking into a valley with no signal
 * leaves the map blank — in an app whose whole point is being outdoors and whose
 * every other feature works offline. Caching the ground around claims is the
 * narrowest fix that matches how the app is actually used: people walk where
 * they have already walked.
 *
 * Pure, so the sizing and eviction rules can be tested without a device, a
 * network, or MapLibre.
 */
object OfflineTilePlanner {

    /**
     * One region per city the walker has claims in.
     *
     * Bounds are the claims' own bounding box, padded so the streets just
     * outside a claim are cached too — you approach a loop before you walk it.
     * The result is clamped to [MAX_SPAN_METERS]: claims scattered across a
     * whole region would otherwise define a box hundreds of kilometres wide,
     * and tile count grows with its area.
     */
    fun plan(territories: List<Territory>): List<PlannedRegion> =
        territories
            .filter { it.isActive && it.city.isNotBlank() && it.ring.isNotEmpty() }
            .groupBy { it.city.trim() }
            .mapNotNull { (city, claims) -> regionFor(city, claims.flatMap { it.ring }) }

    private fun regionFor(city: String, points: List<LatLng>): PlannedRegion? {
        if (points.isEmpty()) return null
        val centre = Geo.centroid(points)

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val latPad = PADDING_METERS / METERS_PER_DEGREE_LAT
        // Degrees of longitude shrink towards the poles, so the same padding in
        // metres is a wider span in degrees the further north or south you are.
        val lngPad = PADDING_METERS / metersPerDegreeLng(centre.lat)

        val halfLatSpan = max((maxLat - minLat) / 2 + latPad, 0.0)
        val halfLngSpan = max((maxLng - minLng) / 2 + lngPad, 0.0)

        val maxHalfLat = (MAX_SPAN_METERS / 2) / METERS_PER_DEGREE_LAT
        val maxHalfLng = (MAX_SPAN_METERS / 2) / metersPerDegreeLng(centre.lat)

        val latHalf = halfLatSpan.coerceAtMost(maxHalfLat)
        val lngHalf = halfLngSpan.coerceAtMost(maxHalfLng)

        // Clamping is measured from the centroid, so an oversized spread keeps
        // the area the walker actually uses rather than a corner of it.
        val south = (centre.lat - latHalf).coerceAtLeast(-85.0)
        val north = (centre.lat + latHalf).coerceAtMost(85.0)
        val west = (centre.lng - lngHalf).coerceAtLeast(-180.0)
        val east = (centre.lng + lngHalf).coerceAtMost(180.0)

        return PlannedRegion(
            city = city,
            southWest = LatLng(south, west),
            northEast = LatLng(north, east),
        )
    }

    /**
     * Which cached cities to delete to get back under [budgetBytes].
     *
     * Least visited goes first, because a cache is only worth its space if it
     * saves a real trip — a city walked once on holiday shouldn't outlive the
     * one walked every week. Ties break on the older visit, so of two equally
     * unused regions the stale one goes. Regions in [keep] are never evicted:
     * they were just planned from current claims, and deleting one would only
     * queue it for immediate re-download.
     */
    fun evictions(
        cached: List<CachedRegion>,
        budgetBytes: Long,
        keep: Set<String> = emptySet(),
    ): List<String> {
        val total = cached.sumOf { it.sizeBytes }
        if (total <= budgetBytes) return emptyList()

        var over = total - budgetBytes
        val victims = mutableListOf<String>()
        cached
            .filter { it.city !in keep }
            .sortedWith(compareBy({ it.visitCount }, { it.lastVisitedAtEpochMs }))
            .forEach { region ->
                if (over <= 0) return@forEach
                victims += region.city
                over -= region.sizeBytes
            }
        return victims
    }

    /** True if [point] falls inside [region] — used to count a visit. */
    fun contains(region: PlannedRegion, point: LatLng): Boolean =
        point.lat in region.southWest.lat..region.northEast.lat &&
            point.lng in region.southWest.lng..region.northEast.lng

    private fun metersPerDegreeLng(lat: Double): Double =
        max(METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)), 1.0)

    /** Close enough at city scale; latitude degrees barely vary. */
    private const val METERS_PER_DEGREE_LAT = 111_195.0

    /** Streets around a claim, so approaching it is covered too. */
    const val PADDING_METERS = 1_500.0

    /**
     * The widest a single city's region may be. Tile count grows with area, so
     * an unclamped box is the difference between tens of megabytes and tens of
     * gigabytes.
     */
    const val MAX_SPAN_METERS = 20_000.0

    /** Street detail without the exponential cost of the last zoom levels. */
    const val MIN_ZOOM = 11.0
    const val MAX_ZOOM = 15.0

    /**
     * Total disk the cache may occupy. Chosen to hold several cities at these
     * zooms while staying well under what a user would notice; the eviction
     * rules above keep it honest.
     */
    const val DEFAULT_BUDGET_BYTES = 300L * 1024 * 1024
}
