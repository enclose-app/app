package io.app.enclose.geo

/**
 * The seam to a map-matching service — the thing that takes a recorded loop and
 * hands back the roads and paths it was actually walked on.
 *
 * Deliberately one function with no host behind it yet, in the same shape as
 * [io.app.enclose.sync.RemoteSyncApi]: [NoRouteMatcher] is what
 * [io.app.enclose.EncloseApp] binds, so nothing snaps and everything else — the
 * schema, the policy, the display rule and their tests — is finished and
 * verifiable. Choosing a host later is one binding.
 *
 * That gap is not an oversight. **There is no free, key-less, terms-clean
 * map-matching endpoint.** The public Valhalla and OSRM instances are community
 * infrastructure under fair-use terms that a published app pointing every user at
 * them would abuse; the commercial ones need an API key, which this app has
 * avoided everywhere (the basemap is keyless OpenFreeMap, the geocoder is the
 * platform's). Resolving that is a decision about what to depend on and what to
 * pay for, not a coding task, and it belongs to whoever ships this.
 *
 * ## What an implementation owes its caller
 *
 * Copy [CityResolver] exactly — it is the app's one existing piece of optional
 * online enrichment and it earns every part of its shape:
 *
 *  - **Never throw.** Null means "no answer right now", the same as a timeout, a
 *    404 or a garbled body. A walk on screen must not be able to crash because a
 *    remote server had a bad day.
 *  - **Hard timeout around the whole call**, not just a socket option.
 *  - **Cap the response size** before parsing it, the way GPX import does.
 *  - **No policy here.** Decode, and hand the geometry to
 *    [io.app.enclose.data.SnapPolicy] to be accepted or refused. This interface
 *    returns what the service said, not what the app should believe.
 *
 * Two wire details worth settling before writing one, because they are easy to
 * get wrong in ways that still look plausible on screen:
 *
 *  - **Precision.** Valhalla encodes polylines at 1e6, not the more common 1e5.
 *    See [Polyline] — the wrong scale decodes without error into a route ten
 *    degrees across.
 *  - **`trace_route` vs `trace_attributes`.** `trace_route` re-routes through
 *    gaps, so a dropped GPS stretch comes back as a fabricated detour that a
 *    short-enough deviation check will not catch; `trace_attributes` with
 *    `shape_match: map_snap` is stricter. `trace_route` also returns one shape
 *    per leg with a repeated vertex at each boundary — see [Polyline.join].
 */
interface RouteMatcher {

    /** True when there is any point calling [match] at all. */
    val isAvailable: Boolean

    /**
     * The roads and paths [ring] was walked on, or null when there is no answer.
     *
     * [ring] is implicitly closed, as everywhere else in this package. The result
     * is whatever the service returned — possibly open, possibly self-crossing,
     * possibly nonsense. Judging it is [io.app.enclose.data.SnapPolicy]'s job.
     */
    suspend fun match(ring: List<LatLng>): List<LatLng>?
}

/**
 * The binding while no host is chosen: reports itself unavailable and matches
 * nothing.
 *
 * Everything downstream is built and tested against this, so wiring a real
 * matcher is a one-line change in [io.app.enclose.EncloseApp] and not a
 * rewrite — and until then the app uploads nothing, which is exactly the
 * behaviour it has always had.
 */
class NoRouteMatcher : RouteMatcher {
    override val isAvailable: Boolean = false
    override suspend fun match(ring: List<LatLng>): List<LatLng>? = null
}
