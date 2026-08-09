package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The tile decoder, tested against tiles built here rather than against a
 * downloaded fixture — a unit test that needs the network is not one.
 *
 * [TileWriter] is the encoder the app deliberately doesn't have: writing the
 * bytes out by hand is what makes the reader's assumptions checkable, and the
 * two halves disagreeing is exactly the failure this is here to catch (the same
 * trick `GpxImporterTest` plays with `GeoExporter.toGpx`).
 */
class MvtTest {

    private val tile = Tile(z = 13, x = 4635, y = 3160)

    @Test
    fun `a line decodes with its tags and its position`() {
        val bytes = TileWriter().layer("transportation") {
            // "1" is written to the tile as an integer value, not a string —
            // see TileWriter — so this also pins that a number decodes back to
            // the same flat map the policy layer reads.
            line(mapOf("class" to "minor", "oneway" to "1"), listOf(0 to 0, 2048 to 2048))
        }.build()

        val lines = Mvt.lines(bytes, tile, "transportation")

        assertEquals(1, lines.size)
        assertEquals("minor", lines[0].tags["class"])
        // Numbers come back as their decimal string; the policy layer reads
        // everything out of one flat map.
        assertEquals("1", lines[0].tags["oneway"])
        assertEquals(2, lines[0].points.size)
        // The tile's north-west corner, and its middle.
        val corner = SlippyTile.toLatLng(tile, 0.0, 0.0, 4096)
        assertEquals(corner.lat, lines[0].points[0].lat, 1e-9)
        assertEquals(corner.lng, lines[0].points[0].lng, 1e-9)
        assertTrue(lines[0].points[1].lat < lines[0].points[0].lat)
        assertTrue(lines[0].points[1].lng > lines[0].points[0].lng)
    }

    @Test
    fun `only the layer asked for is decoded`() {
        val bytes = TileWriter()
            .layer("poi") { line(mapOf("class" to "cafe"), listOf(0 to 0, 10 to 10)) }
            .layer("transportation") { line(mapOf("class" to "path"), listOf(0 to 0, 10 to 10)) }
            .build()

        assertEquals(1, Mvt.lines(bytes, tile, "transportation").size)
        assertEquals("path", Mvt.lines(bytes, tile, "transportation")[0].tags["class"])
        assertEquals(0, Mvt.lines(bytes, tile, "building").size)
    }

    /** Points, polygons and anything else are not lines to walk along. */
    @Test
    fun `non-line geometry is ignored`() {
        val bytes = TileWriter().layer("transportation") {
            point(mapOf("class" to "minor"), 100 to 100)
        }.build()

        assertEquals(0, Mvt.lines(bytes, tile, "transportation").size)
    }

    /**
     * The clip is what stops neighbouring tiles duplicating each other's roads.
     * The margin beyond the tile edge is real data in a real tile, so it has to
     * be cut rather than kept.
     */
    @Test
    fun `geometry past the tile edge is cut at the boundary`() {
        val bytes = TileWriter().layer("transportation") {
            // Starts inside, leaves through the east edge and carries on.
            line(mapOf("class" to "minor"), listOf(2048 to 2048, 6000 to 2048))
        }.build()

        val lines = Mvt.lines(bytes, tile, "transportation")

        assertEquals(1, lines.size)
        val east = SlippyTile.toLatLng(tile, 4096.0, 2048.0, 4096)
        // The far end is the boundary crossing, not the vertex outside it.
        assertEquals(east.lng, lines[0].points.last().lng, 1e-9)
    }

    /**
     * A line that leaves the tile and comes back is two pieces. Joining them
     * would invent a road straight across the corner it went round.
     */
    @Test
    fun `a line that re-enters the tile comes back as two pieces`() {
        val bytes = TileWriter().layer("transportation") {
            line(
                mapOf("class" to "minor"),
                listOf(2000 to 100, 2000 to -500, 3000 to -500, 3000 to 100),
            )
        }.build()

        val lines = Mvt.lines(bytes, tile, "transportation")

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.points.size >= 2 })
    }

    /** A remote server having a bad day must never reach the user as a crash. */
    @Test
    fun `nonsense bytes decode to nothing`() {
        assertEquals(0, Mvt.lines(ByteArray(0), tile, "transportation").size)
        assertEquals(0, Mvt.lines(ByteArray(64) { 0xff.toByte() }, tile, "transportation").size)

        val truncated = TileWriter().layer("transportation") {
            line(mapOf("class" to "minor"), listOf(0 to 0, 100 to 100))
        }.build()
        for (cut in 1 until truncated.size) {
            // Every prefix of a valid tile: some decode to nothing, some to a
            // partial line, none of them throw.
            Mvt.lines(truncated.copyOf(cut), tile, "transportation")
        }
    }
}

/**
 * A minimal Mapbox Vector Tile encoder, for fixtures only.
 *
 * Writes the same field numbers, wire types and command stream the format
 * specifies — see the docs on [Mvt], which this is the mirror image of.
 */
private class TileWriter {

    private val layers = ArrayList<ByteArray>()

    fun layer(name: String, build: LayerWriter.() -> Unit): TileWriter {
        layers.add(LayerWriter(name).apply(build).build())
        return this
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        for (layer in layers) {
            out.writeKey(3, 2)
            out.writeVarint(layer.size.toLong())
            out.write(layer)
        }
        return out.toByteArray()
    }

    class LayerWriter(private val name: String) {
        private val keys = ArrayList<String>()
        private val values = ArrayList<String>()
        private val features = ArrayList<ByteArray>()

        fun line(tags: Map<String, String>, points: List<Pair<Int, Int>>) {
            feature(geomType = 2, tags = tags) {
                writeVarint(command(1, 1).toLong())
                writeVarint(zigZag(points[0].first))
                writeVarint(zigZag(points[0].second))
                writeVarint(command(2, points.size - 1).toLong())
                for (i in 1 until points.size) {
                    writeVarint(zigZag(points[i].first - points[i - 1].first))
                    writeVarint(zigZag(points[i].second - points[i - 1].second))
                }
            }
        }

        fun point(tags: Map<String, String>, at: Pair<Int, Int>) {
            feature(geomType = 1, tags = tags) {
                writeVarint(command(1, 1).toLong())
                writeVarint(zigZag(at.first))
                writeVarint(zigZag(at.second))
            }
        }

        private fun feature(
            geomType: Int,
            tags: Map<String, String>,
            geometry: ByteArrayOutputStream.() -> Unit,
        ) {
            val body = ByteArrayOutputStream()
            val tagIndices = ByteArrayOutputStream()
            for ((key, value) in tags) {
                tagIndices.writeVarint(index(keys, key).toLong())
                tagIndices.writeVarint(index(values, value).toLong())
            }
            val packedTags = tagIndices.toByteArray()
            if (packedTags.isNotEmpty()) {
                body.writeKey(2, 2)
                body.writeVarint(packedTags.size.toLong())
                body.write(packedTags)
            }
            body.writeKey(3, 0)
            body.writeVarint(geomType.toLong())
            val geom = ByteArrayOutputStream().apply(geometry).toByteArray()
            body.writeKey(4, 2)
            body.writeVarint(geom.size.toLong())
            body.write(geom)
            features.add(body.toByteArray())
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            out.writeKey(1, 2)
            val nameBytes = name.toByteArray()
            out.writeVarint(nameBytes.size.toLong())
            out.write(nameBytes)
            // Features before the key/value tables on purpose: the format allows
            // it, and a decoder that assumed otherwise would resolve every tag to
            // nothing.
            for (feature in features) {
                out.writeKey(2, 2)
                out.writeVarint(feature.size.toLong())
                out.write(feature)
            }
            for (key in keys) {
                out.writeKey(3, 2)
                val bytes = key.toByteArray()
                out.writeVarint(bytes.size.toLong())
                out.write(bytes)
            }
            for (value in values) {
                val encoded = ByteArrayOutputStream()
                val asLong = value.toLongOrNull()
                if (asLong != null) {
                    encoded.writeKey(4, 0)
                    encoded.writeVarint(asLong)
                } else {
                    encoded.writeKey(1, 2)
                    val bytes = value.toByteArray()
                    encoded.writeVarint(bytes.size.toLong())
                    encoded.write(bytes)
                }
                val body = encoded.toByteArray()
                out.writeKey(4, 2)
                out.writeVarint(body.size.toLong())
                out.write(body)
            }
            out.writeKey(5, 0)
            out.writeVarint(4096)
            out.writeKey(15, 0)
            out.writeVarint(2)
            return out.toByteArray()
        }

        private fun index(table: MutableList<String>, entry: String): Int {
            val existing = table.indexOf(entry)
            if (existing >= 0) return existing
            table.add(entry)
            return table.size - 1
        }

        private fun command(id: Int, count: Int): Int = (id and 0x7) or (count shl 3)

        private fun zigZag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong()
    }
}

private fun ByteArrayOutputStream.writeKey(field: Int, wireType: Int) {
    writeVarint(((field shl 3) or wireType).toLong())
}

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var v = value
    while (true) {
        val b = (v and 0x7f).toInt()
        v = v ushr 7
        if (v == 0L) {
            write(b)
            return
        }
        write(b or 0x80)
    }
}
