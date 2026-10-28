package io.app.enclose.data

import io.app.enclose.geo.GeoPolygon
import io.app.enclose.geo.LatLng

/** Whether a territory has been pushed to the backend yet. */
enum class SyncStatus { PENDING, SYNCED }

/**
 * A claimed area: the closed ring the walker traced, plus derived stats.
 * [ring] is the as-walked boundary (not explicitly closed — the last point
 * connects back to the first) and never changes. [polygons] is the claimed
 * geometry as a multipolygon, which is [ring] as its only polygon: claims are
 * layers, so a later claim drawn over this one takes nothing from it. Area and
 * export use [polygons]; rendering goes through [SnapDisplay], which may prefer
 * [snappedRing]. The Room layer maps to/from this domain model.
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
    /**
     * City this claim sits in, resolved by reverse geocoding after the claim is
     * saved. Blank until it resolves (offline, or no geocoder on the device) —
     * claiming never waits on it.
     */
    val city: String = "",
    /**
     * Country this claim sits in, resolved alongside [city]. Blank until it
     * resolves, or when the geocoder named a city but no country.
     */
    val country: String = "",
    /**
     * The as-walked boundary matched onto real roads and paths, when that has
     * been done. Empty until it has — and it is allowed to stay empty forever:
     * matching is opt-in, needs a network, and is refused outright when the
     * result doesn't describe the walk (see [SnapPolicy]).
     *
     * **Cosmetic, and only ever cosmetic.** [ring] remains the boundary of
     * record and [areaSqMeters] is measured from it. A footpath missing from the
     * map must never shrink what someone owns.
     */
    val snappedRing: List<LatLng> = emptyList(),
    /**
     * When matching was last attempted, successful or not. Null means never
     * tried, which is what makes it the retry set.
     *
     * Distinct from `snappedRing.isEmpty()` on purpose: a loop round a park or
     * along a beach has no roads to match and will be refused every time, so
     * without a way to record "tried, and the answer was no" every backfill would
     * re-upload the same unmatchable walks forever.
     */
    val snappedAtEpochMs: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
) {
    companion object {
        const val DEFAULT_COLOR = "#7B1FA2"

        /** A single-polygon geometry from a freshly walked ring. */
        fun polygonsFromRing(ring: List<LatLng>): List<GeoPolygon> = listOf(listOf(ring))
    }
}
