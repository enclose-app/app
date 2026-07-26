package io.app.enclose.export

import io.app.enclose.geo.LatLng

/**
 * Reads a GPX track back into points, so a route recorded elsewhere can be
 * replayed into a test-mode walk instead of being tapped out on the map.
 *
 * The counterpart to [GeoExporter.toGpx], and hand-rolled for the same reason
 * the geometry serializers are: `XmlPullParser` and `DocumentBuilderFactory` are
 * both stubbed out in the mockable `android.jar`, so anything built on them
 * cannot be unit tested in this project — and a parser that can't be tested is
 * the wrong place to put the fiddly part of a feature. GPX's point elements are
 * a fixed shape (`lat`/`lon` attributes on an empty-ish tag), which a scanner
 * handles without pretending to be an XML processor.
 *
 * Deliberately lenient about everything it doesn't need. A file from a watch, a
 * phone app and an online route planner will disagree about namespaces,
 * extensions and metadata; none of that changes where the user walked.
 *
 * **It scans with [String.indexOf] and never with `Regex`, and that is load
 * bearing.** `Regex.findAll` builds a fresh `Matcher` over the whole input for
 * every match, and on Android each one copies the input into ICU — so cost grows
 * with the square of the track. It is invisible on a desktop JVM (a 20 000-point
 * ride parses in 113 ms) and ruinous on a phone: the same file took 247 ms at
 * 1 000 points, 1.6 s at 3 000, and had not finished after five minutes at
 * 20 000, with the import dialog up and no way out but killing the app.
 */
object GpxImporter {

    /** One point of the imported route. Elevation is absent more often than not. */
    data class GpxPoint(val position: LatLng, val elevationMeters: Double? = null)

    /**
     * The points of [gpx], in file order, or empty when there are none to find.
     *
     * Track points win outright when present: a file with both a recorded track
     * and a planned route describes one trip, and interleaving the two would
     * invent a path that was never taken. Waypoints are the last resort, since
     * plenty of GPX files carry unrelated pins alongside their track.
     */
    fun parse(gpx: String): List<GpxPoint> {
        // Where every point tag of any kind begins, ascending. Built once: the
        // bound for an `<ele>` lookup is the next point of *any* kind, and
        // searching for one from each point in turn would re-read the rest of
        // the file every time — and read to the very end whenever the file has
        // no tags of that kind, which is the common case.
        val boundaries = POINT_TAGS
            .flatMap { tag -> tagStarts(gpx, tag) }
            .sorted()
        // For the same reason, and it is the worse case of the two: a file with
        // no elevations at all — most of them — would search to the very end of
        // the document once per point and find nothing every time.
        val elevations = tagStarts(gpx, "ele")

        for (tag in POINT_TAGS) {
            val points = pointsOf(gpx, tag, boundaries, elevations)
            if (points.isNotEmpty()) return points
        }
        return emptyList()
    }

    /** Offsets of every `<tag` in [gpx], in one forward pass. */
    private fun tagStarts(gpx: String, tag: String): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (from < gpx.length) {
            val at = gpx.indexOf("<$tag", from, ignoreCase = true)
            if (at < 0) break
            if (isTagBoundary(gpx, at + tag.length + 1)) out += at
            from = at + 1
        }
        return out
    }

    private fun pointsOf(
        gpx: String,
        tag: String,
        boundaries: List<Int>,
        elevations: List<Int>,
    ): List<GpxPoint> {
        val out = ArrayList<GpxPoint>()
        var from = 0
        while (from < gpx.length) {
            val open = gpx.indexOf("<$tag", from, ignoreCase = true)
            if (open < 0) break
            val nameEnd = open + tag.length + 1
            if (!isTagBoundary(gpx, nameEnd)) {
                from = open + 1
                continue
            }
            val close = gpx.indexOf('>', nameEnd)
            if (close < 0) break

            val lat = attribute(gpx, nameEnd, close, "lat")?.toDoubleOrNull()
            val lng = attribute(gpx, nameEnd, close, "lon")?.toDoubleOrNull()
            // A point without usable coordinates is not a point. Skipping it
            // keeps the rest of a slightly malformed file importable, which
            // matters more than being strict about a dev-mode convenience.
            if (lat != null && lng != null && inRange(lat, lng)) {
                out += GpxPoint(
                    position = LatLng(lat, lng),
                    elevationMeters = elevationAfter(
                        gpx = gpx,
                        at = firstAfter(elevations, close),
                        until = firstAfter(boundaries, close),
                    ),
                )
            }
            from = close + 1
        }
        return out
    }

    /** True when the character at [index] ends a tag name rather than continuing it. */
    private fun isTagBoundary(gpx: String, index: Int): Boolean =
        index >= gpx.length || !(gpx[index].isLetterOrDigit() || gpx[index] == '_' || gpx[index] == '-')

    /**
     * The value of [name] among the attributes between [from] and [until].
     *
     * Tolerates the spacing and quoting real files use. Order isn't fixed by the
     * spec — `lon` before `lat` is perfectly legal — so each is found on its own
     * rather than by matching the pair.
     */
    private fun attribute(gpx: String, from: Int, until: Int, name: String): String? {
        var at = from
        while (at < until) {
            val found = gpx.indexOf(name, at, ignoreCase = true)
            if (found < 0 || found >= until) return null
            // Must be a whole attribute name: `lat` inside `xlat` is not one.
            val startsCleanly = found == 0 || !gpx[found - 1].isLetterOrDigit()
            var i = found + name.length
            while (i < until && gpx[i].isWhitespace()) i++
            if (startsCleanly && i < until && gpx[i] == '=') {
                i++
                while (i < until && gpx[i].isWhitespace()) i++
                if (i >= until) return null
                val quote = gpx[i]
                if (quote != '"' && quote != '\'') return null
                val end = gpx.indexOf(quote, i + 1)
                if (end < 0 || end > until) return null
                return gpx.substring(i + 1, end).trim()
            }
            at = found + 1
        }
        return null
    }

    /**
     * The first offset in the (ascending) [offsets] that is after [index], or
     * [Int.MAX_VALUE] when there is none.
     */
    private fun firstAfter(offsets: List<Int>, index: Int): Int {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] <= index) low = mid + 1 else high = mid
        }
        return offsets.getOrNull(low) ?: Int.MAX_VALUE
    }

    /**
     * The elevation opening at [at], if that tag belongs to the point in hand.
     *
     * Elevation is a child element rather than an attribute, so it can only be
     * found by looking ahead — but no further than [until], the next point of
     * any kind. Without that bound, a track with elevations on only some of its
     * points would credit them to their neighbours and invent a climb.
     */
    private fun elevationAfter(gpx: String, at: Int, until: Int): Double? {
        if (at >= until || at >= gpx.length) return null
        val close = gpx.indexOf('>', at)
        if (close < 0 || close >= until) return null
        val end = gpx.indexOf("</ele", close + 1, ignoreCase = true)
        if (end < 0 || end >= until) return null
        return gpx.substring(close + 1, end).trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Coordinates outside these can only be a parse artefact or a broken file. */
    private fun inRange(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            lat >= -90.0 && lat <= 90.0 &&
            lng >= -180.0 && lng <= 180.0

    /** In preference order — see [parse]. */
    private val POINT_TAGS = listOf("trkpt", "rtept", "wpt")
}
