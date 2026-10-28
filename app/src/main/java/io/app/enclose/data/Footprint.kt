package io.app.enclose.data

import io.app.enclose.geo.GeoClip

/**
 * The ground a set of claims covers between them, with shared ground counted
 * once.
 *
 * Claims are layers: a newer claim is drawn over an older one and each keeps
 * the area it was walked with. That makes adding their areas the wrong total.
 * A park walked twice would count as two parks, and city coverage could pass
 * 100%. Every total the app shows — the map's summary, the list, the profile,
 * [Coverage] and [Passport] — comes from here instead.
 *
 * Only claims whose bounding boxes touch can share ground, so they are measured
 * in clusters, each in its own local projection as [GeoClip] does: two cities'
 * claims never share one. Within a cluster the overlap JTS finds is taken off
 * the sum of the claims' own areas, rather than the union's area being used
 * directly, so claims that don't overlap add up to exactly the figure they did
 * before — the stored areas are the ones the claims show, and a total that
 * disagreed with its own parts by a projection's rounding would read as wrong.
 *
 * Pure, and JTS work: callers run it off the main thread.
 */
object Footprint {

    /** Square metres covered by [territories], shared ground counted once. */
    fun areaSqMeters(territories: List<Territory>): Double =
        clusters(territories).sumOf { cluster ->
            val own = cluster.sumOf { it.areaSqMeters }
            if (cluster.size == 1) {
                own
            } else {
                val shared = GeoClip.sharedSqMeters(
                    cluster.map { t -> t.polygons.ifEmpty { Territory.polygonsFromRing(t.ring) } },
                )
                // Never below the biggest claim in it: a union holds each of its
                // members whole, whatever the projections round to.
                (own - shared).coerceAtLeast(cluster.maxOf { it.areaSqMeters })
            }
        }

    /** Groups of claims whose bounding boxes touch, directly or through others. */
    private fun clusters(territories: List<Territory>): List<List<Territory>> {
        val boxes = territories.map { Box.of(it) }
        val parent = IntArray(territories.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }
        for (i in boxes.indices) {
            val a = boxes[i] ?: continue
            for (j in i + 1 until boxes.size) {
                val b = boxes[j] ?: continue
                if (a.touches(b)) parent[root(j)] = root(i)
            }
        }
        return territories.indices.groupBy { root(it) }.values.map { members ->
            members.map { territories[it] }
        }
    }

    private class Box(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        fun touches(o: Box): Boolean =
            minLat <= o.maxLat && o.minLat <= maxLat && minLng <= o.maxLng && o.minLng <= maxLng

        companion object {
            /** Null for a claim with no geometry, which can share nothing. */
            fun of(t: Territory): Box? {
                val points = t.polygons.flatten().flatten().ifEmpty { t.ring }
                if (points.isEmpty()) return null
                return Box(
                    minLat = points.minOf { it.lat },
                    maxLat = points.maxOf { it.lat },
                    minLng = points.minOf { it.lng },
                    maxLng = points.maxOf { it.lng },
                )
            }
        }
    }
}
