package io.app.enclose.data

import io.app.enclose.geo.GeoPolygon
import io.app.enclose.geo.LatLng

/** Whether a territory has been pushed to the backend yet. */
enum class SyncStatus { PENDING, SYNCED }

/**
 * A claimed area: the closed ring the walker traced, plus derived stats.
 * [ring] is the original as-walked boundary (not explicitly closed — the last
 * point connects back to the first). [polygons] is the *effective* claimed
 * geometry, which starts equal to [ring] but shrinks (and can gain holes or
 * split) when later claims overlap and carve into it. Rendering and area use
 * [polygons]. The Room layer maps to/from this domain model.
 */
data class Territory(
    val id: String,
    val name: String,
    val ring: List<LatLng>,
    val polygons: List<GeoPolygon>,
    val areaSqMeters: Double,
    val perimeterMeters: Double,
    val claimedAtEpochMs: Long,
    /** Fill/outline color as a hex string, e.g. "#7B1FA2". */
    val colorHex: String = DEFAULT_COLOR,
    /** Free-form user notes about this territory (shown on the detail screen). */
    val notes: String = "",
    val syncStatus: SyncStatus = SyncStatus.PENDING,
) {
    companion object {
        const val DEFAULT_COLOR = "#7B1FA2"

        /** A single-polygon geometry from a freshly walked ring. */
        fun polygonsFromRing(ring: List<LatLng>): List<GeoPolygon> = listOf(listOf(ring))
    }
}
