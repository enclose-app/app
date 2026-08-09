package io.app.enclose.geo

/**
 * A reader for Mapbox Vector Tiles — the `.pbf` tiles the basemap is already
 * made of, read here for their road geometry rather than to draw them.
 *
 * ## Why this is hand-rolled
 *
 * The route planner needs a walkable road network, and the app has one available
 * without an API key, without new terms to accept and without a second data
 * source to keep in step with the map: the vector tiles it already streams. What
 * it does *not* have is a way to read them — MapLibre decodes tiles internally
 * and only ever hands the result to the renderer, and pulling in a protobuf
 * runtime plus a vector-tile library to read four fields would be a large
 * dependency for a small job.
 *
 * So this decodes the subset of the format the planner needs: line geometry and
 * string/number tags from one named layer. It is in the same idiom as
 * [Polyline] and `GpxImporter` — a codec written out longhand, kept pure, and
 * unit tested — for the same reason those are.
 *
 * ## The format, in the amount of detail this file assumes
 *
 * Protobuf wire format: a stream of `(field number << 3) | wire type` keys, each
 * followed by a varint (type 0), a length-delimited block (type 2), or a fixed
 * 4- or 8-byte value (types 5 and 1). Unknown fields are skipped by wire type,
 * which is what lets this read tiles produced by any encoder.
 *
 *  - `Tile.layers` is field 3.
 *  - A layer carries `name` (1), `features` (2), `keys` (3), `values` (4) and
 *    `extent` (5). Tags are indices into the layer's shared key and value
 *    tables, which is why features can only be decoded once the whole layer has
 *    been scanned — the tables are allowed to follow the features that use them.
 *  - A feature carries `tags` (2, packed), `type` (3) and `geometry` (4, packed).
 *  - Geometry is a command stream: `(command | count << 3)`, where MoveTo (1)
 *    and LineTo (2) each consume two zig-zag encoded deltas per repetition.
 *    Coordinates are cumulative, on a grid of `extent` units across the tile.
 *
 * **Never throws.** A truncated or nonsense tile decodes to no lines, exactly as
 * a failed download would: this parses bytes from a remote server, and a route
 * suggestion that can't be made is a message, never a crash.
 */
object Mvt {

    /** One line of a vector tile layer, in world coordinates. */
    data class Line(
        /** The feature's tags, with numbers rendered as their decimal string. */
        val tags: Map<String, String>,
        /** The line as walked/drawn, already converted out of tile coordinates. */
        val points: List<LatLng>,
    )

    /**
     * Every LineString in [layerName] of [tile], clipped to the tile's own edges.
     *
     * **The clip is load bearing, and so is the fact that it interpolates.**
     * Tiles carry a margin of geometry beyond their boundary so that a renderer
     * has something to draw up to the seam, which means neighbouring tiles
     * repeat each other's roads. Keeping both copies would build a graph full of
     * duplicated, slightly-offset streets. Dropping vertices that fall outside
     * instead of cutting the segment where it crosses would leave a gap at every
     * tile boundary — up to the spacing between two vertices, which is enough to
     * disconnect a road and make a loop that plainly exists impossible to find.
     * Cutting at the boundary leaves both tiles' copies ending on the same line,
     * within the grid's own rounding, which the graph's node snapping then joins.
     */
    fun lines(bytes: ByteArray, tile: Tile, layerName: String): List<Line> =
        runCatching { decodeLines(bytes, tile, layerName) }.getOrDefault(emptyList())

    private fun decodeLines(bytes: ByteArray, tile: Tile, layerName: String): List<Line> {
        val reader = Reader(bytes)
        val out = ArrayList<Line>()
        while (reader.hasMore()) {
            val field = reader.readKey()
            if (field.number == FIELD_TILE_LAYERS && field.wireType == WIRE_LENGTH) {
                val end = reader.blockEnd()
                val name = peekLayerName(bytes, reader.position, end)
                if (name == layerName) out.addAll(readLayer(reader, end, tile))
                reader.seek(end)
            } else {
                reader.skip(field.wireType)
            }
        }
        return out
    }

    /**
     * The layer's name without decoding the rest of it.
     *
     * A planet tile is most of a megabyte and nearly all of it is labels, points
     * of interest and buildings the planner has no use for. Reading the name
     * first means only the one layer asked for is ever walked through.
     */
    private fun peekLayerName(bytes: ByteArray, start: Int, end: Int): String? {
        val reader = Reader(bytes, start, end)
        while (reader.hasMore()) {
            val field = reader.readKey()
            if (field.number == FIELD_LAYER_NAME && field.wireType == WIRE_LENGTH) {
                return reader.readString()
            }
            reader.skip(field.wireType)
        }
        return null
    }

    private fun readLayer(reader: Reader, end: Int, tile: Tile): List<Line> {
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        // Byte ranges, not decoded features: the key and value tables a feature's
        // tags index into may be encoded after the features themselves.
        val features = ArrayList<IntRange>()

        while (reader.position < end) {
            val field = reader.readKey()
            when {
                field.number == FIELD_LAYER_FEATURES && field.wireType == WIRE_LENGTH -> {
                    val blockEnd = reader.blockEnd()
                    features.add(reader.position until blockEnd)
                    reader.seek(blockEnd)
                }
                field.number == FIELD_LAYER_KEYS && field.wireType == WIRE_LENGTH ->
                    keys.add(reader.readString())
                field.number == FIELD_LAYER_VALUES && field.wireType == WIRE_LENGTH -> {
                    val blockEnd = reader.blockEnd()
                    values.add(readValue(reader, blockEnd))
                    reader.seek(blockEnd)
                }
                field.number == FIELD_LAYER_EXTENT && field.wireType == WIRE_VARINT ->
                    extent = reader.readVarint().toInt().coerceIn(1, MAX_EXTENT)
                else -> reader.skip(field.wireType)
            }
        }

        val out = ArrayList<Line>(features.size)
        for (range in features) {
            out.addAll(readFeature(reader.at(range), range.last + 1, keys, values, extent, tile))
        }
        return out
    }

    /**
     * A tag value, as a string.
     *
     * The planner reads `class`, `subclass`, `access` and `foot`, which are all
     * strings, but the same table holds numbers (`oneway`, `layer`), and a tile
     * is free to encode a value with any of the seven types. Rendering
     * everything as its decimal string keeps [Line.tags] one flat map that a
     * policy object can read without knowing how the encoder felt about types.
     */
    private fun readValue(reader: Reader, end: Int): String {
        var result = ""
        while (reader.position < end) {
            val field = reader.readKey()
            result = when {
                field.number == VALUE_STRING && field.wireType == WIRE_LENGTH -> reader.readString()
                field.number == VALUE_FLOAT && field.wireType == WIRE_FIXED32 ->
                    Float.fromBits(reader.readFixed32()).toString()
                field.number == VALUE_DOUBLE && field.wireType == WIRE_FIXED64 ->
                    Double.fromBits(reader.readFixed64()).toString()
                field.number == VALUE_INT && field.wireType == WIRE_VARINT ->
                    reader.readVarint().toString()
                field.number == VALUE_UINT && field.wireType == WIRE_VARINT ->
                    reader.readVarint().toULong().toString()
                field.number == VALUE_SINT && field.wireType == WIRE_VARINT ->
                    zigZag(reader.readVarint()).toString()
                field.number == VALUE_BOOL && field.wireType == WIRE_VARINT ->
                    (reader.readVarint() != 0L).toString()
                else -> {
                    reader.skip(field.wireType)
                    result
                }
            }
        }
        return result
    }

    private fun readFeature(
        reader: Reader,
        end: Int,
        keys: List<String>,
        values: List<String>,
        extent: Int,
        tile: Tile,
    ): List<Line> {
        var geometryType = GEOM_UNKNOWN
        var tags: LongArray = EMPTY_LONGS
        var geometry: LongArray = EMPTY_LONGS

        while (reader.position < end) {
            val field = reader.readKey()
            when {
                field.number == FEATURE_TAGS && field.wireType == WIRE_LENGTH ->
                    tags = reader.readPacked()
                field.number == FEATURE_TYPE && field.wireType == WIRE_VARINT ->
                    geometryType = reader.readVarint().toInt()
                field.number == FEATURE_GEOMETRY && field.wireType == WIRE_LENGTH ->
                    geometry = reader.readPacked()
                else -> reader.skip(field.wireType)
            }
        }
        if (geometryType != GEOM_LINESTRING) return emptyList()

        val properties = HashMap<String, String>(tags.size / 2 + 1)
        var i = 0
        while (i + 1 < tags.size) {
            val key = keys.getOrNull(tags[i].toInt())
            val value = values.getOrNull(tags[i + 1].toInt())
            if (key != null && value != null) properties[key] = value
            i += 2
        }

        return decodeGeometry(geometry).flatMap { raw ->
            clipToTile(raw, extent).map { piece ->
                Line(properties, piece.map { SlippyTile.toLatLng(tile, it.x, it.y, extent) })
            }
        }
    }

    /** A point in tile-local units. Doubles because clipping interpolates. */
    private data class TilePoint(val x: Double, val y: Double)

    /**
     * Walk the command stream into polylines. A MoveTo starts a new one; a
     * ClosePath is accepted and ignored, since a closed way is still just a line
     * to walk along.
     */
    private fun decodeGeometry(commands: LongArray): List<List<TilePoint>> {
        val out = ArrayList<List<TilePoint>>()
        var current = ArrayList<TilePoint>()
        var x = 0.0
        var y = 0.0
        var i = 0
        while (i < commands.size) {
            val header = commands[i++].toInt()
            val command = header and 0x7
            // Clamped against what is actually left: the count is 29 bits, so a
            // corrupt header would otherwise spin for hundreds of millions of
            // iterations before finding out the stream ran out.
            val count = minOf(header ushr 3, (commands.size - i) / 2)
            when (command) {
                CMD_MOVE_TO -> repeat(count) {
                    x += zigZag(commands[i++])
                    y += zigZag(commands[i++])
                    if (current.size >= 2) out.add(current)
                    current = ArrayList()
                    current.add(TilePoint(x, y))
                }
                CMD_LINE_TO -> repeat(count) {
                    x += zigZag(commands[i++])
                    y += zigZag(commands[i++])
                    current.add(TilePoint(x, y))
                }
                CMD_CLOSE_PATH -> Unit
                // An unrecognised command means the stream is no longer being
                // read where a command starts; anything after it is noise.
                else -> return out.also { if (current.size >= 2) it.add(current) }
            }
        }
        if (current.size >= 2) out.add(current)
        return out
    }

    /**
     * Cut [points] into the pieces that lie inside `0..extent`, interpolating at
     * the boundary — see [lines] for why both halves of that matter.
     */
    private fun clipToTile(points: List<TilePoint>, extent: Int): List<List<TilePoint>> {
        val max = extent.toDouble()
        val out = ArrayList<List<TilePoint>>()
        var current = ArrayList<TilePoint>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val clipped = clipSegment(a, b, max)
            if (clipped == null) {
                if (current.size >= 2) out.add(current)
                current = ArrayList()
                continue
            }
            val (start, end) = clipped
            if (current.isEmpty()) {
                current.add(start)
            } else if (!current.last().isSameAs(start)) {
                // The line left the tile and came back: two separate pieces, and
                // joining them would invent a road across the corner.
                if (current.size >= 2) out.add(current)
                current = ArrayList()
                current.add(start)
            }
            current.add(end)
        }
        if (current.size >= 2) out.add(current)
        return out
    }

    /**
     * The part of segment `a→b` inside the box, or null when none of it is.
     * Liang-Barsky: clip the parameter interval against each edge in turn.
     */
    private fun clipSegment(a: TilePoint, b: TilePoint, max: Double): Pair<TilePoint, TilePoint>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.x - a.x
        val dy = b.y - a.y

        for (edge in 0 until 4) {
            val p: Double
            val q: Double
            when (edge) {
                0 -> { p = -dx; q = a.x }
                1 -> { p = dx; q = max - a.x }
                2 -> { p = -dy; q = a.y }
                else -> { p = dy; q = max - a.y }
            }
            if (p == 0.0) {
                // Parallel to this edge, and starting outside it: no overlap at
                // all, however the other edges work out.
                if (q < 0) return null
            } else {
                val r = q / p
                if (p < 0) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        return TilePoint(a.x + t0 * dx, a.y + t0 * dy) to TilePoint(a.x + t1 * dx, a.y + t1 * dy)
    }

    /** Sub-millimetre at any tile size, so this is "the same vertex". */
    private fun TilePoint.isSameAs(other: TilePoint): Boolean =
        kotlin.math.abs(x - other.x) < SAME_POINT && kotlin.math.abs(y - other.y) < SAME_POINT

    private fun zigZag(value: Long): Long = (value ushr 1) xor -(value and 1L)

    /**
     * A cursor over the tile's bytes.
     *
     * Index arithmetic rather than a stream for the same reason `GpxImporter`
     * scans with `indexOf`: this runs over a megabyte of tile on a phone, and
     * anything that copies or re-reads its input turns a route suggestion into a
     * visible pause.
     */
    private class Reader(
        private val bytes: ByteArray,
        var position: Int = 0,
        private val end: Int = bytes.size,
    ) {
        fun hasMore(): Boolean = position < end

        fun at(range: IntRange): Reader = Reader(bytes, range.first, range.last + 1)

        fun seek(to: Int) {
            position = to
        }

        fun readKey(): Field {
            val key = readVarint()
            return Field((key ushr 3).toInt(), (key and 0x7L).toInt())
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= end || shift > 63) throw MalformedTile()
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Where the length-delimited block starting here ends. */
        fun blockEnd(): Int {
            val length = readVarint().toInt()
            val stop = position + length
            if (length < 0 || stop > end) throw MalformedTile()
            return stop
        }

        fun readString(): String {
            val stop = blockEnd()
            val text = String(bytes, position, stop - position, Charsets.UTF_8)
            position = stop
            return text
        }

        /** A packed repeated varint field, as raw (pre zig-zag) values. */
        fun readPacked(): LongArray {
            val stop = blockEnd()
            // Every value is at least one byte, so this never over-allocates by
            // more than the number of multi-byte values.
            val values = LongArray(stop - position)
            var count = 0
            while (position < stop) values[count++] = readVarint()
            return values.copyOf(count)
        }

        fun readFixed32(): Int {
            if (position + 4 > end) throw MalformedTile()
            var value = 0
            for (i in 0 until 4) value = value or ((bytes[position + i].toInt() and 0xff) shl (8 * i))
            position += 4
            return value
        }

        fun readFixed64(): Long {
            if (position + 8 > end) throw MalformedTile()
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((bytes[position + i].toLong() and 0xff) shl (8 * i))
            }
            position += 8
            return value
        }

        /** Step over a field this decoder has no use for. */
        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> readFixed64()
                WIRE_LENGTH -> position = blockEnd()
                WIRE_FIXED32 -> readFixed32()
                else -> throw MalformedTile()
            }
        }
    }

    private class Field(val number: Int, val wireType: Int)

    /** Caught at the entry point and reported as "no lines" — see [lines]. */
    private class MalformedTile : RuntimeException(null, null, false, false)

    private val EMPTY_LONGS = LongArray(0)

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    private const val FIELD_TILE_LAYERS = 3
    private const val FIELD_LAYER_NAME = 1
    private const val FIELD_LAYER_FEATURES = 2
    private const val FIELD_LAYER_KEYS = 3
    private const val FIELD_LAYER_VALUES = 4
    private const val FIELD_LAYER_EXTENT = 5

    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4

    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7

    private const val GEOM_UNKNOWN = 0
    private const val GEOM_LINESTRING = 2

    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    /** The grid every tile in practice uses, and the default the spec gives. */
    private const val DEFAULT_EXTENT = 4096

    /** A sanity bound: an extent past this is a corrupt varint, not a tile. */
    private const val MAX_EXTENT = 1 shl 16

    private const val SAME_POINT = 1e-6
}
