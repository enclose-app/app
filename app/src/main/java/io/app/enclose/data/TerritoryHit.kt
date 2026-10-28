package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * Which claim a tap on the map belongs to.
 *
 * Pure and unit-tested, in the same idiom as [SnapDisplay] and [Footprint]: the
 * map hands over a coordinate and gets back a [Territory] or null, with no
 * MapLibre type anywhere near the decision.
 *
 * **It hit-tests the geometry that is actually drawn**, via [SnapDisplay] — not
 * `Territory.polygons`. Selecting by the as-walked outline while the map draws
 * the road-matched one would mean tapping inside a shape and selecting nothing,
 * or tapping beside it and selecting it, which reads as the map being wrong.
 *
 * Feed it the same list the map is drawing.
 */
object TerritoryHit {

    /**
     * The claim under [point], or null for open ground.
     *
     * Claims are layers, so a tap can land in several at once, and it takes the
     * one **drawn on top**: the selected claim when the tap is inside it, since
     * the map draws the selection above every claim, and otherwise the newest,
     * since the map stacks claims oldest first. Anything else would select a
     * claim the finger can't see. A claim lying entirely under a newer one is
     * reached from the claims list instead, which selects it and so raises it.
     */
    fun at(point: LatLng, territories: List<Territory>, selectedId: String? = null): Territory? {
        val under = territories.filter { Geo.polygonsContain(SnapDisplay.polygonsFor(it), point) }
        return under.firstOrNull { it.id == selectedId }
            ?: under.maxWithOrNull(compareBy<Territory> { it.claimedAtEpochMs }.thenBy { it.id })
    }
}
