package io.app.enclose.data

import io.app.enclose.geo.GeoPolygon

/**
 * The single rule for which geometry a claim is *drawn* with.
 *
 * A claim can carry two outlines: [Territory.ring] as it was walked, held as
 * [Territory.polygons], and [Territory.snappedRing] matched onto real roads.
 * Only one of them can be on screen, and the choice has to be made in exactly
 * one place — which is the reason this is a named object and not a one-line
 * extension property on [Territory].
 *
 * An extension property would be importable from [Footprint], [Coverage],
 * [Passport] and `OfflineTilePlanner`, and the entire safety argument for
 * snapping is that it never reaches any of them: a road matcher is a remote
 * guess, and a guess must not decide how much ground someone owns. A named
 * object makes a leak visible in an import list and in a diff.
 *
 * A matched outline describes the whole loop as walked, which is also exactly
 * what a claim covers: claims are layers, and a later claim drawn over this one
 * takes nothing from it. (Claims used to carve each other, and a cut claim had
 * to stop drawing its matched outline — the ground it described was no longer
 * all the claim's. `MIGRATION_13_14` restored every cut claim, so that rule went
 * with the carving.)
 */
object SnapDisplay {

    /**
     * The polygons to render for [territory].
     *
     * Falls back through matched → stored geometry → as-walked ring, so a claim
     * always draws as something. The last step matters more than it looks: `polygons` is
     * theoretically always populated, but it is decoded from stored JSON, and a
     * claim someone walked is not a thing to render as nothing because a string
     * disagreed.
     */
    fun polygonsFor(territory: Territory): List<GeoPolygon> {
        if (usesSnapped(territory)) {
            return Territory.polygonsFromRing(territory.snappedRing)
        }
        return territory.polygons.ifEmpty {
            if (territory.ring.size >= 3) Territory.polygonsFromRing(territory.ring) else emptyList()
        }
    }

    /** Flat list of the points to frame a camera on. */
    fun pointsFor(territory: Territory): List<io.app.enclose.geo.LatLng> =
        polygonsFor(territory).flatten().flatten()

    /**
     * Whether the matched outline is the one being drawn — for the detail screen,
     * which should say so rather than leaving the user to wonder why an outline
     * doesn't match the wobble they remember walking.
     */
    fun usesSnapped(territory: Territory): Boolean = territory.snappedRing.size >= 3
}
