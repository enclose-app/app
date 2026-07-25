package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.GeoClip
import io.app.enclose.geo.GeoRing

/**
 * What a new claim does to the claims already on the map.
 *
 * Land is never destroyed here. A claim the new ring only partly covers keeps
 * what's left; a claim it covers completely is marked **conquered** rather than
 * deleted, freezing its geometry as it stood when it fell so the walk that
 * earned it survives as history. Deleting was the old behaviour and it silently
 * threw away hours of walking with no undo and no record.
 *
 * Pure and free of Android, DB, and coroutine types: this is the expensive JTS
 * work, so the caller decides which thread it runs on, and it can be tested
 * without either.
 */
object Conquest {

    /**
     * The territories that [conqueror] changes, ready to be written back. Only
     * changed rows are returned — untouched claims are absent, not copied.
     *
     * [existing] should be the active claims; already-conquered ones are ignored
     * so a fallen territory can't be conquered a second time.
     */
    fun carve(
        existing: List<Territory>,
        conqueror: Territory,
        atEpochMs: Long,
    ): List<Territory> {
        val ring: GeoRing = conqueror.ring
        if (ring.size < 3) return emptyList()

        return existing.mapNotNull { other ->
            if (other.id == conqueror.id || !other.isActive) return@mapNotNull null
            if (!GeoClip.overlaps(other.polygons, ring)) return@mapNotNull null

            val reduced = GeoClip.subtract(other.polygons, ring)
            if (reduced.isEmpty()) {
                // Completely swallowed: it stops being on the map, but the walk
                // that earned it is kept exactly as it stood when it fell.
                other.copy(
                    conqueredAtEpochMs = atEpochMs,
                    conqueredById = conqueror.id,
                    syncStatus = SyncStatus.PENDING,
                )
            } else {
                other.copy(
                    polygons = reduced,
                    areaSqMeters = Geo.areaOfPolygons(reduced),
                    syncStatus = SyncStatus.PENDING,
                )
            }
        }
    }
}
