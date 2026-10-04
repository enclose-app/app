package io.app.enclose.geo

/**
 * Removes out-and-back spikes from a recorded path before it becomes a claim.
 *
 * A spike is one or two points far from the path on both sides — out to
 * somewhere and straight back — which is the shape a bad fix leaves: the walker
 * was never there, the provider just said so once. [io.app.enclose.tracking.TrackingManager]
 * holds most of them back live; this catches the rest, including any already on
 * disk in a walk restored after the process died.
 *
 * Deliberately narrow, because what it removes is gone from the claim:
 *
 *  - **At most [MAX_SPIKE_POINTS] points.** Live points are at least 4 m apart, so
 *    a real detour of [MIN_SPIKE_METERS] out and back takes many more fixes than
 *    this. The only walked excursion it could take is one made while the device
 *    was dozing, and that encloses a sliver either way.
 *  - **Both legs long, the base short.** The spike's tip must be over
 *    [MIN_SPIKE_METERS] from the points either side of it — past what
 *    [io.app.enclose.tracking.TrackingManager.MAX_ACCURACY_METERS] lets through as
 *    noise — while those two points sit within [MAX_BASE_RATIO] of that reach of
 *    each other. A corner has a long base; a spike doesn't.
 *  - **The first and last points are never touched**: they are the start anchor
 *    and the closing fix.
 */
object PathSpikes {

    const val MAX_SPIKE_POINTS = 2
    const val MIN_SPIKE_METERS = 50.0
    const val MAX_BASE_RATIO = 0.3

    /** [points] with every spike removed; the same list when there are none. */
    fun remove(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points
        val out = points.toMutableList()
        var removedAny = false
        var i = 0
        while (i < out.size - 2) {
            val length = spikeAfter(out, i)
            if (length == 0) {
                i++
                continue
            }
            repeat(length) { out.removeAt(i + 1) }
            removedAny = true
            // Removing a spike can expose one that started a point earlier.
            i = (i - 1).coerceAtLeast(0)
        }
        return if (removedAny) out else points
    }

    /** How many points after [i] form a spike, or 0. Shortest spike wins. */
    private fun spikeAfter(points: List<LatLng>, i: Int): Int {
        val a = points[i]
        for (length in 1..MAX_SPIKE_POINTS) {
            val j = i + length + 1
            if (j >= points.size) break
            val c = points[j]
            val reach = (i + 1 until j).minOf { k ->
                minOf(Geo.distanceMeters(a, points[k]), Geo.distanceMeters(points[k], c))
            }
            if (reach >= MIN_SPIKE_METERS && Geo.distanceMeters(a, c) <= reach * MAX_BASE_RATIO) {
                return length
            }
        }
        return 0
    }
}
