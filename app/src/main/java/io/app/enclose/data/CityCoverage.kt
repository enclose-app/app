package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * How much of one city the walker has taken. One of these per city they have
 * ever claimed in.
 */
data class CityCoverage(
    /** Resolved city name, or blank when reverse geocoding hasn't answered yet. */
    val city: String,
    val territoryCount: Int,
    val claimedAreaSqMeters: Double,
    /** Area of the box containing every claim in this city — the denominator. */
    val boundingAreaSqMeters: Double,
    /** [claimedAreaSqMeters] as a share of [boundingAreaSqMeters], 0..100. */
    val percent: Double,
) {
    /** True while the city name is still unknown (offline, or no geocoder). */
    val isUnknown: Boolean get() = city.isBlank()

    /** Name to show; unresolved claims are grouped under one honest label. */
    val displayName: String get() = if (isUnknown) UNKNOWN_CITY else city

    companion object {
        const val UNKNOWN_CITY = "Unplaced claims"
    }
}

/**
 * Turns claims into per-city coverage.
 *
 * Coverage is claimed area as a share of the bounding box of the claims **in
 * that city**. Measuring per city rather than across every claim is what makes
 * the number mean anything once someone has walked in more than one place: a
 * single box spanning two cities is mostly the countryside between them, so the
 * old whole-collection figure collapsed towards zero the moment you travelled.
 *
 * Pure and free of Android/DB types so it can be unit tested.
 */
object Coverage {

    /** Per-city coverage, largest claimed area first. */
    fun byCity(territories: List<Territory>): List<CityCoverage> =
        territories
            .groupBy { it.city.trim() }
            .map { (city, claims) -> coverageFor(city, claims) }
            .sortedByDescending { it.claimedAreaSqMeters }

    private fun coverageFor(city: String, claims: List<Territory>): CityCoverage {
        val claimedArea = claims.sumOf { it.areaSqMeters }
        val boxArea = boundingBoxAreaSqMeters(claims.flatMap { it.ring })
        val percent = if (boxArea > 0.0 && claimedArea > 0.0) {
            (claimedArea / boxArea * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }
        return CityCoverage(
            city = city,
            territoryCount = claims.size,
            claimedAreaSqMeters = claimedArea,
            boundingAreaSqMeters = boxArea,
            percent = percent,
        )
    }

    /**
     * Area of the lat/lng bounding box of [points], using the same projection
     * as claim areas so the ratio between them is meaningful. Zero for fewer
     * than three points or a degenerate (single point / colinear) box.
     */
    private fun boundingBoxAreaSqMeters(points: List<LatLng>): Double {
        if (points.size < 3) return 0.0
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        return Geo.polygonAreaSqMeters(
            listOf(
                LatLng(minLat, minLng),
                LatLng(minLat, maxLng),
                LatLng(maxLat, maxLng),
                LatLng(maxLat, minLng),
            ),
        )
    }
}
