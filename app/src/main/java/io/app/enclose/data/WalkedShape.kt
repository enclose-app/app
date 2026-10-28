package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.GeoPolygon
import io.app.enclose.geo.LatLng

/**
 * A claim put back to the shape it was walked with.
 *
 * Claims used to carve each other: a new one cut the ground it shared out of
 * every older claim it overlapped, and marked one it covered completely as
 * conquered. They are layers now — a newer claim is drawn over an older one and
 * takes nothing from it — and this is what undoes the cutting: once for the
 * claims already on a device (`MIGRATION_13_14`), and on every backup restore,
 * so a file saved before the change can't bring a cut claim back.
 *
 * Nothing it needs was lost to the cut. [Territory.ring] is the outline as
 * walked and carving never touched it, so the shape is the ring as the only
 * polygon and the area is measured from the ring the way Stop measured it.
 *
 * Pure, so the decision is tested on the JVM; [TerritoryEntity] supplies the
 * JSON on either side of it.
 */
object WalkedShape {

    data class Shape(val polygons: List<GeoPolygon>, val areaSqMeters: Double)

    /**
     * The walked shape for a claim with this [ring] and stored [polygons], or
     * null when it already has that shape.
     *
     * [cut] is whether the row carries a carve or conquest stamp. The geometry
     * comparison is needed as well, because claims cut before the carve stamp
     * existed (database version 12) carry no record of it.
     *
     * Null too for a ring with fewer than three points: there is no shape to
     * restore, and a claim is never replaced with nothing.
     */
    fun restore(ring: List<LatLng>, polygons: List<GeoPolygon>, cut: Boolean): Shape? {
        if (ring.size < 3) return null
        val walked = Territory.polygonsFromRing(ring)
        if (!cut && polygons == walked) return null
        return Shape(polygons = walked, areaSqMeters = Geo.polygonAreaSqMeters(ring))
    }
}
