package io.app.enclose.export

import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a GPX track back into points.
 *
 * The cases here are the ones real files actually differ on — attribute order,
 * quoting, namespace prefixes, whether elevation is present, whether the file is
 * a recorded track or a planned route. A dev-mode importer that only handles the
 * files this app itself writes would be no use for the thing it exists for,
 * which is replaying a route recorded somewhere else.
 */
class GpxImporterTest {

    @Test
    fun `reads track points in file order`() {
        val points = GpxImporter.parse(
            gpx(
                """
                <trkpt lat="37.9838" lon="23.7275"/>
                <trkpt lat="37.9848" lon="23.7275"/>
                <trkpt lat="37.9848" lon="23.7285"/>
                """,
            ),
        )

        assertEquals(3, points.size)
        assertEquals(LatLng(37.9838, 23.7275), points.first().position)
        assertEquals(LatLng(37.9848, 23.7285), points.last().position)
    }

    @Test
    fun `attribute order and quoting are not fixed by the spec`() {
        val points = GpxImporter.parse(
            gpx(
                """
                <trkpt lon="23.7275" lat="37.9838"/>
                <trkpt lat='37.9848'  lon = '23.7285' >
                """,
            ),
        )

        assertEquals(2, points.size)
        assertEquals(LatLng(37.9838, 23.7275), points[0].position)
        assertEquals(LatLng(37.9848, 23.7285), points[1].position)
    }

    @Test
    fun `elevation is picked up when present`() {
        val points = GpxImporter.parse(
            gpx(
                """
                <trkpt lat="37.9838" lon="23.7275"><ele>112.4</ele></trkpt>
                <trkpt lat="37.9848" lon="23.7275"><ele>130.0</ele></trkpt>
                """,
            ),
        )

        assertEquals(112.4, points[0].elevationMeters!!, 0.001)
        assertEquals(130.0, points[1].elevationMeters!!, 0.001)
    }

    @Test
    fun `an elevation belongs only to its own point`() {
        // Watches drop <ele> when the barometer is unavailable. Looking ahead
        // for one without a stop would hand this point its neighbour's height
        // and invent a climb the walker never made.
        val points = GpxImporter.parse(
            gpx(
                """
                <trkpt lat="37.9838" lon="23.7275"></trkpt>
                <trkpt lat="37.9848" lon="23.7275"><ele>130.0</ele></trkpt>
                """,
            ),
        )

        assertNull("No elevation of its own", points[0].elevationMeters)
        assertEquals(130.0, points[1].elevationMeters!!, 0.001)
    }

    @Test
    fun `a recorded track wins over a planned route`() {
        // One trip, described twice. Interleaving them would invent a path.
        val points = GpxImporter.parse(
            gpx(
                """
                <rte><rtept lat="1.0" lon="1.0"/><rtept lat="2.0" lon="2.0"/></rte>
                <trk><trkseg>
                  <trkpt lat="37.9838" lon="23.7275"/>
                  <trkpt lat="37.9848" lon="23.7275"/>
                </trkseg></trk>
                """,
            ),
        )

        assertEquals(2, points.size)
        assertTrue(
            "Should be the track, not the route",
            points.all { it.position.lat > 37.0 },
        )
    }

    @Test
    fun `a route-only file still imports`() {
        val points = GpxImporter.parse(
            gpx("""<rte><rtept lat="37.9838" lon="23.7275"/><rtept lat="37.9848" lon="23.7275"/></rte>"""),
        )

        assertEquals(2, points.size)
    }

    @Test
    fun `waypoints are the last resort`() {
        val points = GpxImporter.parse(
            gpx("""<wpt lat="37.9838" lon="23.7275"/><wpt lat="37.9848" lon="23.7275"/>"""),
        )

        assertEquals(2, points.size)
    }

    @Test
    fun `several track segments read as one route`() {
        // A watch paused and resumed produces multiple <trkseg>. The walker went
        // on one trip and the points are in order, so they are one path.
        val points = GpxImporter.parse(
            gpx(
                """
                <trk>
                  <trkseg><trkpt lat="37.9838" lon="23.7275"/></trkseg>
                  <trkseg><trkpt lat="37.9848" lon="23.7275"/></trkseg>
                </trk>
                """,
            ),
        )

        assertEquals(2, points.size)
    }

    @Test
    fun `points without usable coordinates are skipped, not fatal`() {
        val points = GpxImporter.parse(
            gpx(
                """
                <trkpt lat="37.9838" lon="23.7275"/>
                <trkpt lat="" lon="23.7285"/>
                <trkpt lat="not-a-number" lon="23.7285"/>
                <trkpt lat="91.0" lon="23.7285"/>
                <trkpt lon="23.7285"/>
                <trkpt lat="37.9848" lon="23.7285"/>
                """,
            ),
        )

        assertEquals("Only the two usable points", 2, points.size)
    }

    @Test
    fun `a file with nothing to import comes back empty`() {
        assertTrue(GpxImporter.parse(gpx("<metadata><name>Empty</name></metadata>")).isEmpty())
        assertTrue(GpxImporter.parse("").isEmpty())
        assertTrue(GpxImporter.parse("not xml at all").isEmpty())
    }

    @Test
    fun `what this app exports, it can read back`() {
        // The round trip is the cheapest guard against the two halves drifting.
        val ring = listOf(
            LatLng(37.9838, 23.7275),
            LatLng(37.9848, 23.7275),
            LatLng(37.9848, 23.7285),
            LatLng(37.9838, 23.7285),
        )
        val territory = Territory(
            id = "t1",
            name = "Round Trip",
            ring = ring,
            polygons = Territory.polygonsFromRing(ring),
            areaSqMeters = 1.0,
            perimeterMeters = 1.0,
            claimedAtEpochMs = 0L,
            colorHex = "#7C4DFF",
        )

        val points = GpxImporter.parse(GeoExporter.toGpx(territory))

        // toGpx closes the ring explicitly, so the first point comes back last.
        assertEquals(ring.size + 1, points.size)
        assertEquals(ring, points.dropLast(1).map { it.position })
        assertEquals(ring.first(), points.last().position)
    }

    @Test(timeout = 10_000)
    fun `a long track parses in linear time`() {
        // The lookup for each point's <ele> used to re-scan the rest of the
        // file, and searching for a tag the file doesn't contain read all the
        // way to the end — so cost grew with the square of the track. 20 000
        // points is an ordinary long ride, and it took over a minute; the whole
        // suite would time out here long before that.
        //
        // Both shapes matter: files without <ele> were the worse case, since
        // every lookup ran off the end of the document without finding one.
        for (elevation in listOf("", "<ele>100.0</ele>")) {
            val body = buildString {
                append("<trk><trkseg>")
                repeat(20_000) {
                    append("""<trkpt lat="37.98${it % 100}" lon="23.72${it % 100}">$elevation</trkpt>""")
                }
                append("</trkseg></trk>")
            }
            assertEquals(20_000, GpxImporter.parse(gpx(body)).size)
        }
    }

    private fun gpx(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        $body
        </gpx>
    """.trimIndent()
}
