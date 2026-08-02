package io.app.enclose.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec every map-matching response arrives in. Two things here have bitten
 * real projects: reading a 1e6 polyline at 1e5 (which doesn't fail, it just
 * returns a route ten degrees wide), and concatenating per-leg shapes without
 * dropping the vertex each leg repeats.
 */
class PolylineTest {

    /** The example from Google's own format documentation. */
    @Test
    fun `decodes the reference polyline at 1e5`() {
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.PRECISION_5)

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lng, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lng, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lng, 1e-5)
    }

    /**
     * The precision trap. Valhalla encodes at 1e6; decoding that at 1e5 gives
     * coordinates ten times too large rather than an error, so nothing downstream
     * would notice until the map drew a route across a continent.
     */
    @Test
    fun `the same string decodes ten times larger at the wrong precision`() {
        val ring = listOf(LatLng(52.5200, 13.4050), LatLng(52.5210, 13.4060))
        val encoded = Polyline.encode(ring, Polyline.PRECISION_6)

        val wrong = Polyline.decode(encoded, Polyline.PRECISION_5)

        assertEquals(525.200, wrong[0].lat, 1e-3)
    }

    @Test
    fun `round trips at 1e6`() {
        val ring = listOf(
            LatLng(52.520008, 13.404954),
            LatLng(52.521180, 13.407700),
            LatLng(52.519900, 13.409100),
            LatLng(-33.868800, 151.209300),
        )

        val decoded = Polyline.decode(Polyline.encode(ring, Polyline.PRECISION_6), Polyline.PRECISION_6)

        assertEquals(ring.size, decoded.size)
        for (i in ring.indices) {
            assertEquals(ring[i].lat, decoded[i].lat, 1e-6)
            assertEquals(ring[i].lng, decoded[i].lng, 1e-6)
        }
    }

    /** Deltas accumulate, so a long route is where rounding drift would show. */
    @Test
    fun `round trips a long route without drifting`() {
        val ring = (0 until 5_000).map { LatLng(52.5 + it * 0.00007, 13.4 + it * 0.00011) }

        val decoded = Polyline.decode(Polyline.encode(ring, Polyline.PRECISION_6), Polyline.PRECISION_6)

        assertEquals(ring.size, decoded.size)
        assertEquals(ring.last().lat, decoded.last().lat, 1e-6)
        assertEquals(ring.last().lng, decoded.last().lng, 1e-6)
    }

    @Test
    fun `handles both signs of delta`() {
        val ring = listOf(
            LatLng(0.0, 0.0),
            LatLng(-1.5, 2.5),
            LatLng(1.5, -2.5),
            LatLng(0.0, 0.0),
        )

        val decoded = Polyline.decode(Polyline.encode(ring, Polyline.PRECISION_6), Polyline.PRECISION_6)

        assertEquals(ring, decoded.map { LatLng(it.lat, it.lng) })
    }

    @Test
    fun `an empty string decodes to nothing`() {
        assertTrue(Polyline.decode("", Polyline.PRECISION_6).isEmpty())
    }

    /**
     * A truncated or corrupt response must degrade to "no snap", exactly as a
     * timeout does. Throwing here would crash a Flow collector showing someone's
     * walk.
     */
    @Test
    fun `malformed input decodes to nothing rather than throwing`() {
        // A value whose continuation bit is set but which then runs out.
        assertTrue(Polyline.decode("_p~iF~ps|U_ul", Polyline.PRECISION_6).isEmpty())
        // Characters below the 63 offset entirely.
        assertTrue(Polyline.decode("", Polyline.PRECISION_6).isEmpty())
        // An odd number of values — a latitude with no longitude.
        assertTrue(Polyline.decode("_p~iF", Polyline.PRECISION_6).isEmpty())
    }

    @Test
    fun `joining legs drops the vertex each one repeats`() {
        val a = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0), LatLng(2.0, 2.0))
        val b = listOf(LatLng(2.0, 2.0), LatLng(3.0, 3.0))
        val c = listOf(LatLng(3.0, 3.0), LatLng(4.0, 4.0))

        val joined = Polyline.join(listOf(a, b, c))

        assertEquals(5, joined.size)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), joined.map { it.lat })
    }

    @Test
    fun `joining keeps a leg that does not continue the previous one`() {
        val a = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))
        val b = listOf(LatLng(5.0, 5.0), LatLng(6.0, 6.0))

        assertEquals(4, Polyline.join(listOf(a, b)).size)
    }

    @Test
    fun `joining skips empty legs`() {
        val a = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))

        assertEquals(2, Polyline.join(listOf(emptyList(), a, emptyList())).size)
        assertTrue(Polyline.join(emptyList()).isEmpty())
    }
}
