package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.geo.RouteMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules around *when* a route is sent anywhere, which matter as much as the
 * matching itself: this is the first thing in the app that would upload a precise
 * record of where somebody walked.
 */
class SnapTaggerTest {

    @Test
    fun `nothing is sent while the setting is off`() = runBlocking {
        val matcher = RecordingMatcher(returns = square(BERLIN, SIDE_DEG))
        val store = FakeStore(listOf(claim("a")))
        val tagger = SnapTagger(store, matcher, enabled = { false }, now = { 42L })

        tagger.tag("a", square(BERLIN, SIDE_DEG))
        tagger.backfill()

        assertEquals(0, matcher.calls)
        assertTrue(store.writes.isEmpty())
        assertEquals(0, tagger.pendingCount())
    }

    @Test
    fun `nothing is sent when no matcher is bound`() = runBlocking {
        val store = FakeStore(listOf(claim("a")))
        val tagger = SnapTagger(store, UnavailableMatcher, enabled = { true }, now = { 42L })

        tagger.tag("a", square(BERLIN, SIDE_DEG))
        tagger.backfill()

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `an accepted match is stored with the time it was made`() = runBlocking {
        val ring = square(BERLIN, SIDE_DEG)
        val matched = ring.map { LatLng(it.lat + 0.00005, it.lng + 0.00005) }
        val store = FakeStore(listOf(claim("a", ring)))
        val tagger = SnapTagger(store, RecordingMatcher(matched), enabled = { true }, now = { 42L })

        tagger.tag("a", ring)

        assertEquals(1, store.writes.size)
        val (id, written, at) = store.writes.single()
        assertEquals("a", id)
        assertEquals(4, written.size)
        assertEquals(42L, at)
    }

    /**
     * The reason `snappedAtEpochMs` exists. A loop with no roads to match onto is
     * refused every time, so without recording the refusal every backfill would
     * upload the same walks again, forever.
     */
    @Test
    fun `a refused match still records the attempt`() = runBlocking {
        val ring = square(BERLIN, SIDE_DEG)
        // Far enough away that SnapPolicy rejects it outright.
        val nonsense = ring.map { LatLng(it.lat + 0.05, it.lng + 0.05) }
        val store = FakeStore(listOf(claim("a", ring)))
        val tagger = SnapTagger(store, RecordingMatcher(nonsense), enabled = { true }, now = { 7L })

        tagger.tag("a", ring)

        val (id, written, at) = store.writes.single()
        assertEquals("a", id)
        assertTrue("a refusal stores no geometry", written.isEmpty())
        assertEquals("but it does store the attempt", 7L, at)
    }

    /**
     * Silence is not an answer about the walk — it is an answer about the
     * network, so the claim has to stay in the retry set.
     */
    @Test
    fun `no answer at all records nothing`() = runBlocking {
        val ring = square(BERLIN, SIDE_DEG)
        val store = FakeStore(listOf(claim("a", ring)))
        val tagger = SnapTagger(store, RecordingMatcher(null), enabled = { true }, now = { 7L })

        tagger.tag("a", ring)

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `backfill walks everything that has never been asked about`() = runBlocking {
        val ring = square(BERLIN, SIDE_DEG)
        val matched = ring.map { LatLng(it.lat + 0.00005, it.lng + 0.00005) }
        val store = FakeStore(listOf(claim("a", ring), claim("b", ring), claim("c", ring)))
        val tagger = SnapTagger(store, RecordingMatcher(matched), enabled = { true }, now = { 1L })

        tagger.backfill()

        assertEquals(listOf("a", "b", "c"), store.writes.map { it.first })
    }

    /**
     * Three misses in a row means the service is unreachable, not that these
     * particular walks are unmatchable — so it stops rather than working through
     * a whole history against a dead endpoint.
     */
    @Test
    fun `backfill gives up after three silent answers in a row`() = runBlocking {
        val claims = (1..10).map { claim("t$it") }
        val matcher = RecordingMatcher(null)
        val tagger = SnapTagger(FakeStore(claims), matcher, enabled = { true }, now = { 1L })

        tagger.backfill()

        assertEquals(3, matcher.calls)
    }

    @Test
    fun `a ring too small to be a ring is never sent`() = runBlocking {
        val matcher = RecordingMatcher(square(BERLIN, SIDE_DEG))
        val tagger = SnapTagger(FakeStore(), matcher, enabled = { true }, now = { 1L })

        tagger.tag("a", listOf(BERLIN, LatLng(BERLIN.lat, BERLIN.lng + SIDE_DEG)))

        assertEquals(0, matcher.calls)
    }

    /** The count the UI shows before uploading anything is zero when it's off. */
    @Test
    fun `the pending count respects the setting`() = runBlocking {
        val store = FakeStore(listOf(claim("a"), claim("b")))

        assertEquals(
            2,
            SnapTagger(store, RecordingMatcher(null), enabled = { true }).pendingCount(),
        )
        assertEquals(
            0,
            SnapTagger(store, RecordingMatcher(null), enabled = { false }).pendingCount(),
        )
    }

    /** The setting is read per call, so turning it off mid-session takes effect. */
    @Test
    fun `disabling between claims stops the next one being sent`() = runBlocking {
        val ring = square(BERLIN, SIDE_DEG)
        val matcher = RecordingMatcher(ring.map { LatLng(it.lat + 0.00005, it.lng) })
        var on = true
        val tagger = SnapTagger(FakeStore(), matcher, enabled = { on }, now = { 1L })

        tagger.tag("a", ring)
        on = false
        tagger.tag("b", ring)

        assertEquals(1, matcher.calls)
    }

    // --- fakes ---------------------------------------------------------------

    private class RecordingMatcher(private val returns: List<LatLng>?) : RouteMatcher {
        var calls = 0
            private set
        var lastSent: List<LatLng>? = null
            private set

        override val isAvailable = true

        override suspend fun match(ring: List<LatLng>): List<LatLng>? {
            calls++
            lastSent = ring
            return returns
        }
    }

    private object UnavailableMatcher : RouteMatcher {
        override val isAvailable = false
        override suspend fun match(ring: List<LatLng>): List<LatLng>? = null
    }

    private class FakeStore(private val pending: List<Territory> = emptyList()) : SnapStore {
        val writes = mutableListOf<Triple<String, List<LatLng>, Long>>()

        override suspend fun withoutSnap(): List<Territory> = pending
        override suspend fun withoutSnapCount(): Int = pending.size
        override suspend fun setSnappedRing(id: String, ring: List<LatLng>, atEpochMs: Long) {
            writes.add(Triple(id, ring, atEpochMs))
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun square(at: LatLng, sizeDeg: Double): List<LatLng> = listOf(
        at,
        LatLng(at.lat, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng + sizeDeg),
        LatLng(at.lat + sizeDeg, at.lng),
    )

    private fun claim(id: String, ring: List<LatLng> = square(BERLIN, SIDE_DEG)) = Territory(
        id = id,
        name = "Claim $id",
        ring = ring,
        polygons = Territory.polygonsFromRing(ring),
        areaSqMeters = Geo.polygonAreaSqMeters(ring),
        perimeterMeters = Geo.pathLengthMeters(ring),
        claimedAtEpochMs = 0L,
    )

    private companion object {
        val BERLIN = LatLng(52.5200, 13.4050)
        const val SIDE_DEG = 0.003
    }
}
