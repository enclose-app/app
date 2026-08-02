package io.app.enclose.geo

import kotlin.math.roundToLong

/**
 * The encoded-polyline format, which is how every map-matching service on the
 * wire hands back a route.
 *
 * Values are zig-zag encoded deltas in 5-bit chunks, offset by 63 so the result
 * is printable ASCII. The only part that catches people out is [precision]:
 * Google's original format scales by 1e5, and Valhalla — the shape most likely
 * to be on the other end of [RouteMatcher] — scales by **1e6**. Decoding a
 * 1e6 polyline as 1e5 doesn't fail, it silently returns a route ten degrees
 * wide, so the scale is a parameter with no default.
 *
 * **Scanned with a manual index loop, never `Regex` or `split`, and that is load
 * bearing.** This is the same lesson `GpxImporter` records: a matched route is
 * thousands of vertices, and on ART every `Regex` match copies the input into
 * ICU, so cost grows with the square of the route. The JVM hides it completely.
 * The loop below reads each character exactly once.
 */
object Polyline {

    /** Google's original scale — 5 decimal places, ~1.1 m at the equator. */
    const val PRECISION_5 = 1e5

    /** Valhalla's scale — 6 decimal places. Its map-matching responses use this. */
    const val PRECISION_6 = 1e6

    /**
     * Decode [encoded] into points, or an empty list if it is malformed.
     *
     * Malformed input returns empty rather than throwing: this parses a remote
     * response, and a matcher having a bad day must degrade to "no snap" exactly
     * like a timeout does — never to a crash on a screen showing someone's walk.
     */
    fun decode(encoded: String, precision: Double): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val points = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lng = 0L

        while (index < encoded.length) {
            val dLat = readValue(encoded, index) ?: return emptyList()
            index = dLat.next
            val dLng = readValue(encoded, index) ?: return emptyList()
            index = dLng.next

            lat += dLat.value
            lng += dLng.value
            points.add(LatLng(lat / precision, lng / precision))
        }
        return points
    }

    /** Encode [points] for the wire. Round trips with [decode] at the same scale. */
    fun encode(points: List<LatLng>, precision: Double): String {
        val out = StringBuilder(points.size * 8)
        var lat = 0L
        var lng = 0L
        for (p in points) {
            val scaledLat = (p.lat * precision).roundToLong()
            val scaledLng = (p.lng * precision).roundToLong()
            writeValue(out, scaledLat - lat)
            writeValue(out, scaledLng - lng)
            // Track the *rounded* values, not the originals: the decoder
            // accumulates these deltas, so anything else drifts along the route.
            lat = scaledLat
            lng = scaledLng
        }
        return out.toString()
    }

    /**
     * Stitch the per-leg shapes a routing response comes back as.
     *
     * Every leg after the first repeats the previous leg's final point, so naive
     * concatenation plants a duplicate vertex at each boundary. Those read as
     * zero-length segments, which is a division by zero waiting to happen in any
     * bearing or speed calculation downstream.
     */
    fun join(legs: List<List<LatLng>>): List<LatLng> {
        val out = ArrayList<LatLng>()
        for (leg in legs) {
            if (leg.isEmpty()) continue
            if (out.isNotEmpty() && out.last() == leg.first()) {
                out.addAll(leg.subList(1, leg.size))
            } else {
                out.addAll(leg)
            }
        }
        return out
    }

    /** One decoded value and where the scanner got to. */
    private class Chunk(val value: Long, val next: Int)

    /**
     * Read one zig-zag value starting at [start], or null if the input runs out
     * mid-value or the chunk count is implausible (a truncated response).
     */
    private fun readValue(encoded: String, start: Int): Chunk? {
        var index = start
        var shift = 0
        var result = 0L
        while (true) {
            if (index >= encoded.length) return null
            // More than six chunks cannot be a coordinate at any precision we
            // use, and continuing would overflow the shift.
            if (shift > 30) return null
            val b = encoded[index].code - 63
            if (b < 0) return null
            index++
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        // Low bit set means the value was negative before zig-zag encoding.
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return Chunk(value, index)
    }

    private fun writeValue(out: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }
}
