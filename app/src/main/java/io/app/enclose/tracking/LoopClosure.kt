package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * Where a recorded route's loop ends: its last return to the start.
 *
 * Serves both ways a route ends somewhere other than its start. A recorded
 * track is stopped whenever its recorder was — often after walking on past the
 * start to the car, or the front door — so a GPX import is replayed only up to
 * here. And a live walk can be stopped anywhere: someone who forgot to press
 * Stop and walked (or drove) on home still closed a loop, and [LoopVerdict]
 * claims the loop that closed here rather than throwing the walk away. Either
 * way what comes after is left out of the claim — never out of the record.
 *
 * Pure, so the choice of where to cut is tested on plain points.
 */
object LoopClosure {

    /**
     * The index of the point to end the replay on, or null when the track never
     * comes back within [closeRadiusMeters] of its first point after leaving
     * [leaveRadiusMeters] and covering [minPerimeterMeters] — the same three
     * conditions the tracker needs for a loop to close.
     *
     * The *last* return, not the closest: a track that passes its start halfway
     * and then walks a second lap must keep the second lap. Within that last
     * return — the run of consecutive points inside the radius — the point
     * nearest the start is chosen, so the closing gap is as short as the track
     * allows.
     */
    fun endIndex(
        points: List<LatLng>,
        closeRadiusMeters: Double,
        leaveRadiusMeters: Double,
        minPerimeterMeters: Double,
    ): Int? {
        if (points.size < 3) return null
        val start = points.first()
        var walked = 0.0
        var hasLeft = false
        // A point qualifies once the walk has left the start and is long enough.
        val qualifies = BooleanArray(points.size)
        for (i in 1 until points.size) {
            walked += Geo.distanceMeters(points[i - 1], points[i])
            val fromStart = Geo.distanceMeters(start, points[i])
            if (fromStart > leaveRadiusMeters) hasLeft = true
            qualifies[i] = hasLeft && walked >= minPerimeterMeters && fromStart <= closeRadiusMeters
        }
        val last = qualifies.indexOfLast { it }
        if (last < 0) return null
        var first = last
        while (first - 1 >= 0 && qualifies[first - 1]) first--
        return (first..last).minBy { Geo.distanceMeters(start, points[it]) }
    }

    /** How far the track's last point ends from its first, for saying why it can't close. */
    fun endGapMeters(points: List<LatLng>): Double =
        if (points.size < 2) 0.0 else Geo.distanceMeters(points.first(), points.last())
}
