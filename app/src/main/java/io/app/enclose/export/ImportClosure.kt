package io.app.enclose.export

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * Where an imported track should end so that its loop closes.
 *
 * A live walk is stopped by the walker, standing at the start. A recorded track
 * is stopped whenever its recorder was — often after walking on past the start
 * to the car, or the front door. Replayed as-is, the walk then "ends" out there
 * and can't close, though the walker plainly came back round. So the replay
 * stops at the walker's last return to the start instead, and the overshoot is
 * left out of the claim (never out of the file).
 *
 * Pure, so the choice of where to cut is tested on plain points.
 */
object ImportClosure {

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
