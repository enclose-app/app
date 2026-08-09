package io.app.enclose.data

import io.app.enclose.geo.FamiliarGround
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.LoopPlanner
import io.app.enclose.geo.PathGraph
import io.app.enclose.geo.WalkableArea
import io.app.enclose.geo.WalkableWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a suggested route came from — which is the difference between "you've
 * done this one" and "nobody has walked this yet, including you".
 */
enum class RouteOrigin {
    /** A loop this walker has closed before, offered again. */
    WALKED_BEFORE,

    /** Planned over the basemap's roads and paths. */
    PLANNED,
}

/** Why there is no suggestion. Each one needs a different thing said about it. */
enum class RouteUnavailable {
    /**
     * There is no position to plan from. Every suggestion starts from where the
     * walker is standing, so without a fix there is no question to answer — and
     * this is emphatically not the same as "nothing found here", which is what
     * the user would read from any of the others.
     */
    NO_FIX,

    /**
     * The device is offline, and this is the one feature in Enclose that
     * requires being online.
     *
     * Checked up front rather than discovered from a failed fetch, and it stops
     * previously walked routes being offered too — a planner that answered some
     * of the time offline, with the "you've walked this before" suggestions
     * only, would look like the shuffle button had broken.
     */
    OFFLINE,

    /** Online, but the tiles didn't arrive. */
    NO_DATA,

    /** The walker is nowhere near a mapped road or path. */
    NO_PATHS_NEARBY,

    /** Roads there are, but no loop of about the right length among them. */
    NO_LOOP,

    /** The distance asked for is outside what one suggestion can cover. */
    OUT_OF_RANGE,
}

/** A route to walk, and everything the UI needs to describe it honestly. */
data class RouteSuggestion(
    val route: List<LatLng>,
    val lengthMeters: Double,
    val origin: RouteOrigin,
    /** When this was last walked, for [RouteOrigin.WALKED_BEFORE]. */
    val walkedAtEpochMs: Long? = null,
    /** How much of it runs over ground already claimed, 0..1. */
    val familiarFraction: Double = 0.0,
    /** How far the walker is from where it begins. */
    val startsAwayMeters: Double = 0.0,
    /** Which press of the button produced this, so the next one differs. */
    val attempt: Int = 0,
)

/** Either a route, or the reason there isn't one. */
sealed interface RouteOutcome {
    data class Found(val suggestion: RouteSuggestion) : RouteOutcome
    data class None(val reason: RouteUnavailable) : RouteOutcome
}

/**
 * Suggests a walk of the length you ask for, starting from where you are.
 *
 * ## The order it offers things in
 *
 * 1. **Routes already walked**, nearest the asked-for distance first — see
 *    [PastRoutes] for why evidence beats a guess.
 * 2. **Loops planned over the basemap**, biased towards ground already claimed
 *    ([FamiliarGround]) so what comes back is recognisable rather than a random
 *    tour of the borough.
 *
 * [RouteRequest.attempt] walks down that list: it is what the shuffle button
 * increments, and it makes the sequence deterministic — the same press over the
 * same data gives the same route, so a rotation or a trip through the background
 * doesn't quietly swap the route out from under the walker.
 *
 * ## Everything starts from the walker
 *
 * There is no "plan a route somewhere else" here, deliberately. A suggestion is
 * for setting off on now, so a route that begins across town is not a worse
 * suggestion, it is the wrong thing entirely — which is why a previous walk is
 * only offered when its near end is within
 * [PastRoutes.MAX_START_METERS] and a planned loop is refused outright when
 * there is no mapped path within [PathGraph.NEAR_START_METERS] of where the
 * walker is standing.
 */
class RouteSuggester(private val area: WalkableArea) {

    suspend fun suggest(request: RouteRequest): RouteOutcome {
        if (request.targetMeters < MIN_TARGET_METERS ||
            request.targetMeters > MAX_TARGET_METERS
        ) {
            return RouteOutcome.None(RouteUnavailable.OUT_OF_RANGE)
        }

        val walked = PastRoutes.matching(
            walks = request.pastWalks,
            from = request.from,
            targetMeters = request.targetMeters,
        )
        walked.getOrNull(request.attempt)?.let { match ->
            return RouteOutcome.Found(
                RouteSuggestion(
                    route = match.route,
                    lengthMeters = match.lengthMeters,
                    origin = RouteOrigin.WALKED_BEFORE,
                    walkedAtEpochMs = match.walkedAtEpochMs,
                    // It was walked and (usually) claimed, so it is familiar by
                    // definition; measuring it against the claims would only
                    // restate that.
                    familiarFraction = 1.0,
                    startsAwayMeters = match.startsAwayMeters,
                    attempt = request.attempt,
                ),
            )
        }

        // Enough ground for the loop to wander into, without fetching a county.
        // The planner aims about a quarter of the target out and refines from
        // there, so half the target as a radius leaves it room to overshoot.
        val radius = (request.targetMeters * AREA_RADIUS_FRACTION)
            .coerceAtLeast(MIN_AREA_RADIUS_METERS)
        val ways = area.ways(request.from, radius) ?: return RouteOutcome.None(
            RouteUnavailable.NO_DATA,
        )

        // Graph building and the searches over it are hundreds of thousands of
        // operations — nowhere near the frame clock, exactly like Conquest.carve.
        return withContext(Dispatchers.Default) {
            val graph = graphFor(request.from, ways)
            if (graph.nearestNode(request.from) == null) {
                return@withContext RouteOutcome.None(RouteUnavailable.NO_PATHS_NEARBY)
            }
            val familiar = FamiliarGround.of(request.claimRings)
            val seed = request.attempt - walked.size
            val loop = LoopPlanner.plan(
                graph = graph,
                start = request.from,
                targetMeters = request.targetMeters,
                seed = seed,
                familiar = familiar,
            ) ?: return@withContext RouteOutcome.None(RouteUnavailable.NO_LOOP)

            RouteOutcome.Found(
                RouteSuggestion(
                    route = loop.points,
                    lengthMeters = loop.lengthMeters,
                    origin = RouteOrigin.PLANNED,
                    familiarFraction = familiar.familiarFraction(loop.points),
                    startsAwayMeters = 0.0,
                    attempt = request.attempt,
                ),
            )
        }
    }

    /**
     * The network around [from], built once and kept for as long as the walker
     * stays put.
     *
     * The shuffle button is why. Turning tiles into a graph is a few hundred
     * thousand operations — 150 ms for one city on a desktop JVM, and this
     * codebase has already learned once, expensively, that a JVM figure says
     * nothing about what ART will do with it (see `GpxImporter`). Rebuilding it
     * on every press would put that between the user and each new suggestion,
     * for a graph that is identical every time.
     *
     * One entry, keyed on where the walker is: they are standing still while
     * they decide, and a fix that wanders further than [SAME_PLACE_METERS] is
     * far enough that the roads around them have genuinely changed.
     */
    private suspend fun graphFor(from: LatLng, ways: List<WalkableWay>): PathGraph =
        graphLock.withLock {
            val cached = graph
            if (cached != null && Geo.distanceMeters(graphCenter!!, from) < SAME_PLACE_METERS) {
                return@withLock cached
            }
            PathGraph.build(ways).also {
                graph = it
                graphCenter = from
            }
        }

    private var graph: PathGraph? = null
    private var graphCenter: LatLng? = null
    private val graphLock = Mutex()

    companion object {
        /**
         * How far the walker can drift before the cached network is rebuilt.
         * Comfortably inside the margin the tile fetch leaves around them.
         */
        private const val SAME_PLACE_METERS = 150.0

        /**
         * Shorter than this and there is no loop to speak of — the closing zone
         * a walk has to leave and come back to is 60 m across on its own.
         */
        const val MIN_TARGET_METERS = 500.0

        /**
         * The longest single suggestion. Past a marathon the tiles needed run
         * into the megabytes ([io.app.enclose.geo.OpenFreeMapWalkableArea]) and
         * the search space grows with them, for a walk nobody is setting off on
         * from a standing start on a phone screen.
         */
        const val MAX_TARGET_METERS = 25_000.0

        private const val AREA_RADIUS_FRACTION = 0.5
        private const val MIN_AREA_RADIUS_METERS = 900.0
    }
}

/**
 * What to suggest, and what the walker already has.
 *
 * The claims and walks are passed in rather than read from a repository here:
 * they are already in memory on the screen asking for this, and taking them as
 * arguments keeps the suggester a pure function of its inputs apart from the
 * one tile fetch.
 */
data class RouteRequest(
    /** Where the walker is standing, now. */
    val from: LatLng,
    val targetMeters: Double,
    /** 0 for the first suggestion, then one per press of shuffle. */
    val attempt: Int,
    /** Every closed loop this walker has recorded. */
    val pastWalks: List<Walk> = emptyList(),
    /** The outlines of what they hold, for the familiarity bias. */
    val claimRings: List<List<LatLng>> = emptyList(),
)
