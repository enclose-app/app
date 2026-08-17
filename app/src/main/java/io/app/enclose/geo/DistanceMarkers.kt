package io.app.enclose.geo

/**
 * A milestone on a walked path: the point at which the walk passed a whole
 * multiple of the marker spacing.
 *
 * [distanceMeters] is the distance *along the path*, not the marker's distance
 * from anything on screen — so a marker sitting a few metres from the start of a
 * loop can legitimately read "5".
 */
data class DistanceMarker(
    val position: LatLng,
    /** 1-based: the first marker is 1 km in, the second 2 km, and so on. */
    val index: Int,
    /** Distance walked to reach this marker, in metres (= index × spacing). */
    val distanceMeters: Double,
)

/**
 * Where to put the "you have walked another kilometre" ticks along a path.
 *
 * Split out from the map for the usual reason: this is the part that can be
 * wrong in ways nobody sees. A marker placed at the *fix* nearest each kilometre
 * rather than at the kilometre itself drifts by however far apart the fixes are
 * — a walk recorded at 3 s intervals puts a fix every ~4 m, but one stretch with
 * no signal is a single straight segment kilometres long, and dropping one
 * marker on it (or none) is exactly the walk where the user is looking for them.
 * So markers are interpolated *into* the segment they fall in, and a segment
 * long enough for several gets several.
 *
 * The distances are measured with [Geo.distanceMeters] over the same points
 * `TrackingManager` accumulates its own total from, so a marker can never
 * disagree with the distance on the panel.
 */
object DistanceMarkers {

    /** One marker per kilometre — what the map draws unless told otherwise. */
    const val DEFAULT_SPACING_METERS = 1000.0

    /**
     * Ceiling on markers returned, so a pathological call (a tiny spacing, or a
     * path that grew past anything a person walks) can't hand the map tens of
     * thousands of features to re-upload on every fix. 200 km of markers is far
     * past the longest walk this app is for, and the cap truncates the far end
     * rather than thinning: the markers a walker cares about are the ones behind
     * them.
     */
    const val MAX_MARKERS = 200

    /**
     * Markers every [spacingMeters] along [path], in order.
     *
     * Positions are linearly interpolated within the segment each falls in.
     * Straight-line interpolation over a segment is what the map draws for that
     * segment anyway, so the marker lands *on* the drawn line — which is the
     * property that matters here, and one great-circle interpolation would
     * quietly break.
     */
    fun along(
        path: List<LatLng>,
        spacingMeters: Double = DEFAULT_SPACING_METERS,
        limit: Int = MAX_MARKERS,
    ): List<DistanceMarker> {
        if (path.size < 2 || spacingMeters <= 0.0 || limit <= 0) return emptyList()

        val markers = ArrayList<DistanceMarker>()
        // Distance to the start of the segment being walked, and the next
        // milestone still owed. Both are absolute along-path distances, so
        // rounding can't accumulate the way a per-segment remainder would.
        var travelled = 0.0
        var next = spacingMeters
        for (i in 1 until path.size) {
            val from = path[i - 1]
            val to = path[i]
            val segment = Geo.distanceMeters(from, to)
            if (segment <= 0.0) continue
            // A while, not an if: one segment can span several milestones when
            // the recording lost signal and bridged the gap with a straight line.
            while (next <= travelled + segment) {
                val fraction = (next - travelled) / segment
                markers += DistanceMarker(
                    position = LatLng(
                        lat = from.lat + (to.lat - from.lat) * fraction,
                        lng = from.lng + (to.lng - from.lng) * fraction,
                    ),
                    index = markers.size + 1,
                    distanceMeters = next,
                )
                if (markers.size >= limit) return markers
                next += spacingMeters
            }
            travelled += segment
        }
        return markers
    }
}
