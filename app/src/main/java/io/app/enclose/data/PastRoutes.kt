package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlin.math.abs

/**
 * Walks already done, offered back as routes to walk again.
 *
 * This is the first thing a route suggestion looks at, ahead of anything
 * generated, and the reason is worth stating: a route somebody has actually
 * walked is *known* to work. It has no missing pavement, no locked gate, no
 * footbridge that turned out to be a railway crossing — and the loop closed on
 * it once already, which is the whole game in this app. A generated loop is a
 * guess made from a basemap; this is evidence.
 *
 * It is also what the user asked for in as many words: offer something very
 * close, or the same walk.
 *
 * Pure Kotlin over domain objects, so the matching rules are unit tested rather
 * than discovered on a Sunday.
 */
object PastRoutes {

    /** A previous walk that would suit the distance asked for. */
    data class Match(
        /** The walk it came from; shares its id with the claim, if it was claimed. */
        val walkId: String,
        /**
         * The ring, closed and rotated to begin at the point nearest the walker
         * — so the line on the map starts where they'd join it rather than at
         * whatever corner they happened to set off from last time.
         */
        val route: List<LatLng>,
        val lengthMeters: Double,
        val walkedAtEpochMs: Long,
        /** How far the walker is from where this route begins. */
        val startsAwayMeters: Double,
    )

    /**
     * Previous walks close to [from] and close to [targetMeters], best first.
     *
     * "Best" is nearest the asked-for distance, and recency breaks ties: two
     * loops of the same length are equally good walks, and the newer one is the
     * one the walker is more likely to still recognise.
     */
    fun matching(
        walks: List<Walk>,
        from: LatLng,
        targetMeters: Double,
        tolerance: Double = TOLERANCE,
        maxStartMeters: Double = MAX_START_METERS,
    ): List<Match> {
        if (targetMeters <= 0) return emptyList()
        val allowed = targetMeters * tolerance
        return walks.mapNotNull { walk ->
            val ring = walk.ring
            if (ring.size < MIN_RING_POINTS) return@mapNotNull null

            var nearest = 0
            var nearestMeters = Double.MAX_VALUE
            for ((index, point) in ring.withIndex()) {
                val distance = Geo.distanceMeters(from, point)
                if (distance < nearestMeters) {
                    nearestMeters = distance
                    nearest = index
                }
            }
            // Starts from where you are, or it isn't a route you can set off on.
            if (nearestMeters > maxStartMeters) return@mapNotNull null

            // Rotated so the walker joins it at the near end, then closed —
            // rings are implicitly closed everywhere in this app, and a route to
            // follow has to show the last stretch back to the start.
            val rotated = ring.subList(nearest, ring.size) + ring.subList(0, nearest)
            val route = rotated + rotated.first()
            // Measured from the geometry rather than read from
            // [Walk.perimeterMeters]: the figure shown and the line drawn have
            // to be the same thing, and the stored one was measured before any
            // of this existed.
            val length = Geo.pathLengthMeters(route)
            if (abs(length - targetMeters) > allowed) return@mapNotNull null

            Match(
                walkId = walk.id,
                route = route,
                lengthMeters = length,
                walkedAtEpochMs = walk.closedAtEpochMs,
                startsAwayMeters = nearestMeters,
            )
        }.sortedWith(
            compareBy<Match> { abs(it.lengthMeters - targetMeters) }
                .thenByDescending { it.walkedAtEpochMs },
        )
    }

    /**
     * How far off the asked-for distance a previous walk may be.
     *
     * Wider than the planner's own tolerance on purpose. A generated loop can be
     * asked to try again at a different radius until it lands; a walk that has
     * been done is the length it is, and refusing a 5.4 km loop to somebody who
     * asked for 5 km — in favour of a route generated from a basemap — would be
     * preferring the guess to the evidence.
     */
    const val TOLERANCE = 0.2

    /**
     * How far the walker may be from a previous route before it stops counting
     * as one they can set off on now.
     *
     * Suggestions start from where you are standing. Half a kilometre is a few
     * minutes' walk to the near end of the loop; much beyond that and the
     * "route" is mostly the journey to it.
     */
    const val MAX_START_METERS = 500.0

    /** Below this it isn't a ring, whatever the row says. */
    private const val MIN_RING_POINTS = 3
}
