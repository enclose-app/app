package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * Which claim a tap on the map belongs to.
 *
 * Pure and unit-tested, in the same idiom as [SnapDisplay] and [Conquest]: the
 * map hands over a coordinate and gets back a [Territory] or null, with no
 * MapLibre type anywhere near the decision.
 *
 * **It hit-tests the geometry that is actually drawn**, via [SnapDisplay] — not
 * `Territory.polygons`. Selecting by the as-walked outline while the map draws
 * the road-matched one would mean tapping inside a shape and selecting nothing,
 * or tapping beside it and selecting it, which reads as the map being wrong.
 *
 * Feed it the same list the map is drawing. Conquered claims are absent from
 * `observeActive()` and so are unselectable, which is right: they are not on
 * screen, and a tap can only mean the thing under the finger.
 */
object TerritoryHit {

    /**
     * The claim under [point], or null for open ground.
     *
     * Claims do not normally overlap — a new one carves the old — but a tap can
     * still land in more than one where a claim sits inside another's hole, and
     * carving is done on the geometry of record while this reads the displayed
     * one. The **smallest** claim wins those, because it is the more specific
     * answer and the harder of the two to hit any other way; ties go to the
     * newer claim.
     */
    fun at(point: LatLng, territories: List<Territory>): Territory? =
        territories
            .filter { Geo.polygonsContain(SnapDisplay.polygonsFor(it), point) }
            .minWithOrNull(
                compareBy<Territory> { it.areaSqMeters }.thenByDescending { it.claimedAtEpochMs },
            )
}
