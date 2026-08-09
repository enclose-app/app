package io.app.enclose.geo

/**
 * A stretch of road or path a person is allowed to walk, and how much this app
 * wants to send them down it.
 *
 * [comfort] is a cost multiplier, not a score: 1.0 is an ordinary residential
 * street, below 1 is somewhere nicer, above 1 is somewhere you'd rather not
 * spend a kilometre. The planner multiplies it by real distance, so a park path
 * has to be under about a third longer to beat a main road, which is roughly the
 * detour a person would actually make.
 */
data class WalkableWay(
    val points: List<LatLng>,
    val comfort: Double,
    /**
     * Which deck this is on where ways cross: 0 at ground level, positive for a
     * bridge, negative for a tunnel.
     *
     * It exists for one job, in [PathGraph]: deciding whether two ways that
     * *touch on the map* actually meet. A footbridge and the road beneath it
     * cross at a point on screen and are not a junction, and a router that
     * joined them would send someone over a parapet.
     */
    val level: Int = 0,
)

/**
 * Which lines out of a vector tile a route may be planned along.
 *
 * The rule the user gave is the first one here: **no motorways or trunk roads.**
 * That is not only a comfort question — a motorway is usually illegal and always
 * unsafe on foot, and a router that hands someone a slip road because it was
 * 200 m shorter has failed at the only part of the job that can hurt them. So
 * this is an *allowlist*: a class nobody has thought about is not walkable.
 * Vector tiles gain classes over time (busways, raceways, piers), and the
 * failure mode of guessing wrong points one way.
 *
 * Everything here reads [Mvt.Line.tags] as plain strings, which is what keeps it
 * pure and unit tested rather than eyeballed against one city's tiles.
 *
 * The tags come from the OpenMapTiles schema the basemap is built to:
 *  - `class` — `motorway`, `trunk`, `primary`, `secondary`, `tertiary`, `minor`,
 *    `service`, `track`, `path`, plus non-road classes (`rail`, `transit`,
 *    `ferry`, `aerialway`) and `*_construction` variants.
 *  - `subclass` — the original OSM value, so `path` splits into `footway`,
 *    `steps`, `cycleway`, `crossing`, and `minor` into `living_street`.
 *  - `access` / `foot` — `private`, `no` and friends, which is the only thing in
 *    the tile that says "not for you".
 */
object WalkableWays {

    /** The layer roads live in, in every OpenMapTiles-schema basemap. */
    const val LAYER = "transportation"

    /**
     * The way to walk [line], or null when it isn't one.
     *
     * Lines of fewer than two points are dropped here rather than downstream:
     * they carry no length, and a zero-length edge is a division by zero waiting
     * to happen in anything that takes a bearing along one.
     */
    fun of(line: Mvt.Line): WalkableWay? {
        if (line.points.size < 2) return null
        val tags = line.tags
        if (isForbidden(tags)) return null
        val comfort = comfortOf(tags) ?: return null
        return WalkableWay(points = line.points, comfort = comfort, level = levelOf(tags))
    }

    /**
     * Which deck a way is on — see [WalkableWay.level].
     *
     * `layer` when the data has it, and otherwise inferred from `brunnel`: a
     * bridge with no explicit layer is still over something, and a tunnel is
     * still under it. Ways that share an endpoint are joined regardless of this
     * (that is how a bridge meets the road it lands on); it only governs a way
     * that *crosses* another.
     */
    private fun levelOf(tags: Map<String, String>): Int {
        tags["layer"]?.toDoubleOrNull()?.let { return it.toInt() }
        return when (tags["brunnel"]) {
            "bridge" -> 1
            "tunnel" -> -1
            else -> 0
        }
    }

    /** Every walkable way in a decoded tile, in one pass. */
    fun of(lines: List<Mvt.Line>): List<WalkableWay> = lines.mapNotNull(::of)

    /**
     * Explicitly closed to people on foot.
     *
     * `indoor` is in here because indoor corridors are mapped as walkable paths
     * and route perfectly well through a shopping centre that is shut, and
     * because a loop that claims territory through the inside of a building is
     * not what anybody asked for.
     */
    private fun isForbidden(tags: Map<String, String>): Boolean {
        if (tags["indoor"] == "1" || tags["indoor"] == "true") return true
        val foot = tags["foot"]
        if (foot != null && foot in DENIED_ACCESS) return true
        // `access` is the general restriction; `foot` overrides it, which is why
        // a private drive with a public footpath along it still routes.
        val access = tags["access"]
        return access != null && access in DENIED_ACCESS && (foot == null || foot !in ALLOWED_FOOT)
    }

    /** The cost multiplier for this line, or null when it isn't walkable. */
    private fun comfortOf(tags: Map<String, String>): Double? {
        val roadClass = tags["class"] ?: return null
        if (roadClass !in WALKABLE_CLASSES) return null
        SUBCLASS_COMFORT[tags["subclass"]]?.let { return it }
        return CLASS_COMFORT[roadClass]
    }

    /**
     * The classes a person may be sent along. `motorway` and `trunk` are absent
     * on purpose and are the whole point — see the class docs.
     */
    private val WALKABLE_CLASSES = setOf(
        "path",
        "track",
        "minor",
        "service",
        "tertiary",
        "secondary",
        "primary",
    )

    private val CLASS_COMFORT = mapOf(
        // Paths and tracks are what somebody out for a walk is hoping for.
        "path" to 0.7,
        "track" to 0.8,
        // Residential and unclassified streets: the baseline everything else is
        // judged against.
        "minor" to 1.0,
        // Driveways, parking aisles and alleys — walkable, rarely pleasant, and
        // frequently a dead end that costs the walk a there-and-back.
        "service" to 1.4,
        "tertiary" to 1.15,
        "secondary" to 1.6,
        // Not banned: in some towns the only way across a river is the main road.
        // Expensive enough that any reasonable detour wins.
        "primary" to 2.4,
    )

    /**
     * Where the original OSM value says more than the class does.
     *
     * Steps are the interesting one: they are perfectly walkable and often the
     * nicest way up a hill, but they rule the route out for anyone with a pram
     * or a bike, and they wreck a distance estimate because nobody walks up them
     * at walking pace. Costly rather than banned.
     */
    private val SUBCLASS_COMFORT = mapOf(
        "pedestrian" to 0.65,
        "footway" to 0.7,
        "living_street" to 0.8,
        "cycleway" to 0.9,
        // Kerb-to-kerb links: cheap, and the route falls apart without them.
        "crossing" to 1.0,
        "steps" to 2.0,
    )

    private val DENIED_ACCESS = setOf("no", "private", "customers", "permit", "military")
    private val ALLOWED_FOOT = setOf("yes", "designated", "permissive", "official")
}
