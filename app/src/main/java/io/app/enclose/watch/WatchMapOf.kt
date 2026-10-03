package io.app.enclose.watch

import io.app.enclose.data.SnapDisplay
import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.RouteSimplify
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.watchlink.GeoPoint
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchMap

/**
 * The walk, as the watch's map draws it. Pure: the simplification and the size
 * budget are tested here.
 *
 * The path is simplified before it goes — a straight street walked at one fix
 * every 3 s is dozens of points that draw as one line on a 1.4" screen — and
 * if a very long walk still runs over [WatchMap.MAX_POINTS], the tolerance is
 * doubled until it fits. The shape coarsens; the walk is never cut short.
 */
fun watchMapOf(walk: TrackingManager.WalkState, closeRadiusMeters: Double): WatchMap {
    if (!walk.isTracking) return WatchMap()
    var tolerance = RouteSimplify.DEFAULT_TOLERANCE_METERS
    var path = RouteSimplify.simplify(walk.path, tolerance)
    while (path.size > WatchMap.MAX_POINTS) {
        tolerance *= 2
        path = RouteSimplify.simplify(walk.path, tolerance)
    }
    return WatchMap(
        startedAtEpochMs = walk.startedAtMs,
        path = path.map { it.toGeoPoint() },
        start = walk.start?.toGeoPoint(),
        current = walk.current?.toGeoPoint(),
        closeRadiusMeters = closeRadiusMeters,
    )
}

/**
 * The claims worth drawing under a walk at [center]: the nearest
 * [WatchClaims.MAX_CLAIMS] with any of their outline within [radiusMeters].
 *
 * Drawn from what the phone draws ([SnapDisplay]), so a claim matched onto real
 * paths has the same outline on both screens.
 */
fun watchClaimsNear(
    territories: List<Territory>,
    center: LatLng,
    radiusMeters: Double = NEARBY_CLAIMS_RADIUS_M,
): WatchClaims {
    val nearest = territories
        .map { territory ->
            territory to SnapDisplay.pointsFor(territory).minOfOrNull { Geo.distanceMeters(center, it) }
        }
        .filter { (_, distance) -> distance != null && distance <= radiusMeters }
        .sortedBy { (_, distance) -> distance }
        .take(WatchClaims.MAX_CLAIMS)
        .map { (territory, _) -> territory }
    return WatchClaims(
        nearest.map { territory ->
            WatchClaims.Claim(
                color = argbOf(territory.colorHex),
                polygons = SnapDisplay.polygonsFor(territory).map { polygon ->
                    polygon.map { ring ->
                        RouteSimplify.simplifyRing(ring, CLAIM_TOLERANCE_M).map { it.toGeoPoint() }
                    }
                },
            )
        },
    )
}

/**
 * "#RRGGBB" or "#AARRGGBB" as ARGB. Here rather than `android.graphics.Color`
 * so it runs in a JVM test; an unreadable colour falls back to the default
 * claim colour rather than leaving the claim off the map.
 */
internal fun argbOf(hex: String): Int {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16)
        ?: return argbOf(Territory.DEFAULT_COLOR)
    return when (digits.length) {
        6 -> (0xFF000000 or value).toInt()
        8 -> value.toInt()
        else -> argbOf(Territory.DEFAULT_COLOR)
    }
}

private fun LatLng.toGeoPoint() = GeoPoint(lat, lng)

/** A long walk is a few km out from its start; past this, claims are off-screen. */
private const val NEARBY_CLAIMS_RADIUS_M = 6_000.0

/** Claim outlines are background on the watch; 5 m is well under a pixel there. */
private const val CLAIM_TOLERANCE_M = 5.0
