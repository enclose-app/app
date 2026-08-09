package io.app.enclose.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Where the roads and paths around a point come from.
 *
 * A seam, in the same idiom as [RouteMatcher] and
 * [io.app.enclose.sync.RemoteSyncApi] — but unlike those, this one has a real
 * implementation bound, because the answer to "where does walkable road data
 * come from without an API key" turned out to already be in the app: the vector
 * basemap it draws.
 */
interface WalkableArea {

    /**
     * Every stretch of path within roughly [radiusMeters] of [center], or null
     * when there is no answer right now.
     *
     * Null covers being offline, a server having a bad day, and an area so large
     * it would be unreasonable to fetch — the caller turns it into a message,
     * never a crash and never a retry loop.
     */
    suspend fun ways(center: LatLng, radiusMeters: Double): List<WalkableWay>?
}

/**
 * Reads walkable roads out of the same key-less OpenFreeMap vector tiles the
 * basemap is drawn from.
 *
 * ## Why zoom 13
 *
 * The transportation layer is complete at zoom 14, and a zoom 14 tile of a city
 * is around 700 KB — of which the roads are a few percent and the rest is
 * labels, buildings and points of interest that get decoded past and thrown
 * away. Zoom 13 covers four times the ground for a quarter of the bytes: the
 * same streets, footpaths and pedestrian areas, generalised to about a metre,
 * which is far finer than a route needs. A 5 km loop is four tiles instead of
 * nine, and roughly a fifteenth of the data.
 *
 * That matters because of what this app has always said about other people's
 * data allowances: the offline downloader refuses to run on a metered network at
 * all. This runs when the user presses a button and asks for a route, which is
 * different — but "a couple of hundred kilobytes on request" is what makes it
 * different, so the zoom, the tile cap and the cache are all part of the
 * feature, not tuning.
 *
 * ## Never throws
 *
 * Copied from [CityResolver], which is the app's other piece of optional online
 * enrichment: a hard timeout around the whole call, a cap on how much is read
 * before parsing, and null for every kind of failure. A walk in progress must
 * not be able to end because a tile server was slow.
 */
class OpenFreeMapWalkableArea(
    /** The TileJSON describing the tiles; the same one the basemap style names. */
    private val tileJsonUrl: String = DEFAULT_TILEJSON_URL,
) : WalkableArea {

    /**
     * Decoded tiles, keyed by address.
     *
     * The point of the cache is the shuffle button: asking for another
     * suggestion re-searches the same few square kilometres, and without this
     * every press would re-download them. Small on purpose — a tile's worth of
     * ways is around a megabyte in memory, and the working set is one search
     * area.
     */
    private val cache = object : LinkedHashMap<Tile, List<WalkableWay>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Tile, List<WalkableWay>>) =
            size > CACHED_TILES
    }

    private val cacheLock = Mutex()

    /** The tile URL template, resolved once from the TileJSON and kept. */
    private var tileTemplate: String? = null
    private val templateLock = Mutex()

    override suspend fun ways(center: LatLng, radiusMeters: Double): List<WalkableWay>? {
        val bounds = GeoBounds.around(center, radiusMeters)
        val tiles = SlippyTile.cover(bounds, ZOOM)
        // Refused rather than silently trimmed: a caller asking for this much
        // ground has asked for the wrong thing, and half an area produces loops
        // that mysteriously only ever go one way.
        if (tiles.isEmpty() || tiles.size > MAX_TILES) return null

        val template = template() ?: return null
        val out = ArrayList<WalkableWay>()
        var fetched = 0
        for (tile in tiles) {
            val ways = cached(tile) ?: fetchTile(template, tile)?.also {
                fetched++
                cacheLock.withLock { cache[tile] = it }
            }
            // One missing tile is a hole in the map, not a reason to abandon the
            // search: the loop simply won't be planned through it.
            if (ways != null) out.addAll(ways)
        }
        // Nothing at all came back and nothing was cached — that is being
        // offline, and saying "no paths here" would blame the neighbourhood.
        if (out.isEmpty() && fetched == 0) return null
        return out
    }

    private suspend fun cached(tile: Tile): List<WalkableWay>? = cacheLock.withLock { cache[tile] }

    /**
     * The `{z}/{x}/{y}` template, fetched once per process.
     *
     * It carries a dated planet build (`.../planet/20260802_080001_pt/...`), so
     * it can't be hard-coded — but it also changes about as often as the planet
     * is rebuilt, and a route suggestion isn't worth a second round trip every
     * time.
     */
    private suspend fun template(): String? = templateLock.withLock {
        tileTemplate?.let { return it }
        val json = get(tileJsonUrl, MAX_TILEJSON_BYTES)?.toString(Charsets.UTF_8) ?: return null
        val resolved = firstTileUrl(json) ?: return null
        tileTemplate = resolved
        resolved
    }

    /**
     * The first entry of TileJSON's `"tiles"` array.
     *
     * Scanned with [String.indexOf] rather than parsed: this is two fields deep
     * in a document whose other 60 000 characters describe every layer in the
     * basemap, and `GpxImporter` records at length what happens on Android when
     * something reaches for a regex over a large input.
     */
    private fun firstTileUrl(json: String): String? {
        val key = json.indexOf("\"tiles\"")
        if (key < 0) return null
        val open = json.indexOf('[', key)
        if (open < 0) return null
        val first = json.indexOf('"', open)
        if (first < 0) return null
        val end = json.indexOf('"', first + 1)
        if (end < 0) return null
        return json.substring(first + 1, end).takeIf { it.contains("{z}") }
    }

    private suspend fun fetchTile(template: String, tile: Tile): List<WalkableWay>? {
        val url = template
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
        val bytes = get(url, MAX_TILE_BYTES) ?: return null
        // Decoding a megabyte of protobuf is real work; it does not belong on
        // whatever thread the caller happened to be on.
        return withContext(Dispatchers.Default) {
            WalkableWays.of(Mvt.lines(bytes, tile, WalkableWays.LAYER))
        }
    }

    /** A GET with a hard deadline and a hard size cap, or null. */
    private suspend fun get(url: String, maxBytes: Int): ByteArray? =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching { read(url, maxBytes) }.getOrNull()
            }
        }

    private fun read(url: String, maxBytes: Int): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { stream ->
                val body = stream.readNBytes(maxBytes)
                // Some CDNs hand back gzip whichever way the request was framed.
                // The magic number is the only reliable way to know, since the
                // platform decompresses transparently when it added the header
                // itself and not when it didn't.
                if (body.size >= 2 && body[0] == GZIP_MAGIC_0 && body[1] == GZIP_MAGIC_1) {
                    GZIPInputStream(body.inputStream()).use { it.readCapped(maxBytes) }
                } else {
                    body
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readCapped(maxBytes: Int): ByteArray = readNBytes(maxBytes)

    private companion object {
        /**
         * The same host and dataset the basemap streams from — see
         * `EncloseMap`'s style URLs. No key, and nothing new to agree to.
         */
        const val DEFAULT_TILEJSON_URL = "https://tiles.openfreemap.org/planet"

        /** See the class docs: complete enough to route on, a quarter the bytes. */
        const val ZOOM = 13

        /**
         * A hard stop on how much is fetched for one suggestion. Twenty zoom-13
         * tiles is roughly 200 km² and a few megabytes — past any loop somebody
         * is going to walk in an afternoon.
         */
        const val MAX_TILES = 20

        /** One search area's worth, so shuffling costs nothing. */
        const val CACHED_TILES = 8

        const val REQUEST_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /** Well past a planet tile; anything larger is not one. */
        const val MAX_TILE_BYTES = 8_000_000

        /** The TileJSON is ~60 KB of layer descriptions. */
        const val MAX_TILEJSON_BYTES = 1_000_000

        const val USER_AGENT = "Enclose/1.0 (route planner)"

        const val GZIP_MAGIC_0 = 0x1f.toByte()
        const val GZIP_MAGIC_1 = 0x8b.toByte()
    }
}
