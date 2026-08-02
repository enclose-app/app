package io.app.enclose.data

import io.app.enclose.geo.LatLng
import io.app.enclose.geo.RouteSimplify
import io.app.enclose.geo.RouteMatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The slice of storage [SnapTagger] needs, as an interface so the tagger can be
 * tested without Room — the same seam [WalkProgressStore] gives
 * [io.app.enclose.tracking.WalkProgressRecorder], and for the same reason: the
 * decisions here (whether to ask at all, what a refusal costs, when to give up)
 * are worth pinning, and none of them are reachable through a real database in a
 * plain JVM test.
 *
 * Narrow on purpose. A tagger that could reach the rest of [TerritoryRepository]
 * would be a tagger that could rewrite a boundary.
 */
interface SnapStore {
    suspend fun withoutSnap(): List<Territory>
    suspend fun withoutSnapCount(): Int
    suspend fun setSnappedRing(id: String, ring: List<LatLng>, atEpochMs: Long)
}

/**
 * Keeps [Territory.snappedRing] filled in.
 *
 * Structurally the same as [CityTagger] — matching needs a network and claiming
 * must not, so it runs *after* the claim is saved and is simply retried later if
 * it fails. Three things differ, and each is a consequence of what is on the
 * other end:
 *
 *  - **[tag] takes the lock too**, not just [backfill]. `CityTagger` can let those
 *    race because the geocoder is on-device, free and cached; a rate-limited
 *    third-party endpoint the user had to opt into is none of those.
 *  - **A refusal is recorded, not just a success.** `snappedAtEpochMs` is stamped
 *    either way, so a loop round a park with no roads to match onto is asked
 *    about once rather than on every backfill forever.
 *  - **It is opt-in and it never bulk-uploads by itself.** [tag] runs for new
 *    claims when the user has turned matching on; [backfill] only ever runs
 *    because someone pressed a button that told them how many walks it would
 *    send.
 *
 * ## What this must never do
 *
 * Write anything but `snappedJson`/`snappedAtEpochMs`. [Territory.ring] is the
 * boundary of record, [Territory.areaSqMeters] is measured from it, and
 * [Conquest] carves with it. A road matcher is a remote guess, and a guess does
 * not get to decide how much ground someone owns. The write goes through
 * [TerritoryRepository.setSnappedRing], which is a targeted UPDATE for exactly
 * that reason.
 *
 * ## A race it does not try to win
 *
 * `EncloseViewModel.confirmClaim` snapshots the territory list before its
 * coroutine starts, and the whole-row upsert at the end of carving writes that
 * snapshot back. A snap that lands in between is reverted. This is the same race
 * `city` has always had, and it self-heals the same way: the reverted row has a
 * null `snappedAtEpochMs` again, so the next backfill picks it up. Worth knowing
 * about; not worth a transaction to prevent.
 */
class SnapTagger(
    private val repository: SnapStore,
    private val matcher: RouteMatcher,
    /** Whether the user has turned matching on. Read per call, never cached. */
    private val enabled: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = Mutex()

    /** True when a backfill would do anything at all, for the UI to decide on. */
    val isAvailable: Boolean get() = matcher.isAvailable

    /** How many claims [backfill] would upload. */
    suspend fun pendingCount(): Int = if (enabled()) repository.withoutSnapCount() else 0

    /**
     * Match one claim. Silent no-op when matching is off or unavailable.
     *
     * Refusals are recorded as deliberately as successes — see the class KDoc.
     */
    suspend fun tag(territoryId: String, ring: List<LatLng>) {
        if (!enabled() || !matcher.isAvailable || ring.size < 3) return
        lock.withLock { matchAndStore(territoryId, ring) }
    }

    /**
     * Match every claim that has never been offered to the matcher.
     *
     * Only ever called from an explicit user action, because unlike the geocoder
     * this sends precise routes to someone else's server: turning a switch on
     * must not, by itself, upload a walking history.
     *
     * Gives up after [MAX_CONSECUTIVE_FAILURES] in a row, exactly as [CityTagger]
     * does — that many misses in sequence means the service is unreachable, not
     * that these particular walks are unmatchable, and the next call picks up
     * where this one stopped.
     */
    suspend fun backfill() {
        if (!enabled() || !matcher.isAvailable) return
        // A second caller would only re-send what the first is already sending.
        if (!lock.tryLock()) return
        try {
            var consecutiveFailures = 0
            for (territory in repository.withoutSnap()) {
                if (territory.ring.size < 3) continue
                val matched = matchAndStore(territory.id, territory.ring)
                if (matched == null) {
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
                } else {
                    consecutiveFailures = 0
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Returns the verdict, or null when the service gave no answer at all.
     *
     * The distinction drives the give-up counter above and nothing else: a
     * *refusal* is a real answer about that walk and is stored, whereas silence
     * says something about the network and must not be recorded as "asked and
     * answered" — the walk deserves another try when the signal is back.
     */
    private suspend fun matchAndStore(territoryId: String, ring: List<LatLng>): SnapVerdict? {
        // Thinned before it goes over the wire: a long walk is thousands of
        // fixes, most of which are GPS noise the matcher would discard anyway.
        // Metre-scale, so this can't be what cuts a corner — see RouteSimplify.
        val toSend = RouteSimplify.simplifyRing(ring)
        val matched = matcher.match(toSend) ?: return null

        val verdict = SnapPolicy.judge(ring, matched)
        val accepted = (verdict as? SnapVerdict.Accepted)?.ring ?: emptyList()
        repository.setSnappedRing(territoryId, accepted, now())
        return verdict
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
