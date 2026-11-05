package io.app.enclose.data

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A made-up history for screenshots and demos: claims across three cities, the
 * walks behind them, and one walk that didn't count. Pure and deterministic —
 * the same set every time, so a screenshot can be retaken.
 *
 * It only ever lands in the demo database ([DemoMode]), never beside real
 * claims: a walked territory can't be re-created from the couch, and a demo
 * row mixed in with them would be one more thing to get wrong when deleting.
 */
object DemoData {

    /** Where the map opens in demo mode: Barcelona, over the densest claims. */
    val camera = MapCamera(lat = 41.3945, lng = 2.1700, zoom = 13.6)

    /** The profile's name and how long ago it was "created". */
    const val FIRST_NAME = "Maya"
    const val LAST_NAME = "Torres"
    const val MEMBER_DAYS = 214

    data class Set(
        val territories: List<Territory>,
        val walks: List<Walk>,
        val voided: List<VoidedWalk>,
    )

    /** One claim to draw: where, how big, what shape, when. */
    private class Spec(
        val name: String,
        val city: City,
        /** Centre, as metres east and north of the city's anchor. */
        val east: Double,
        val north: Double,
        /** Half-widths in metres, before the shape's wobble. */
        val rx: Double,
        val ry: Double,
        val rotationDeg: Double,
        val color: String,
        val daysAgo: Int,
        val hour: Int,
        val notes: String = "",
    )

    private enum class City(val anchor: LatLng, val cityName: String, val country: String) {
        BARCELONA(LatLng(41.3925, 2.1700), "Barcelona", "Spain"),
        LISBON(LatLng(38.7139, -9.1394), "Lisbon", "Portugal"),
        PARIS(LatLng(48.8566, 2.3522), "Paris", "France"),
    }

    // The claim dialog's swatches (Format.kt), so every colour is one a user could pick.
    private const val PURPLE = "#7B1FA2"
    private const val ORCHID = "#AB47BC"
    private const val BLUE = "#1E88E5"
    private const val AMBER = "#F2A65A"
    private const val RED = "#E53935"
    private const val TEAL = "#00897B"

    // Oldest first, as they were walked. Overlaps are on purpose: claims are
    // layers, and a map of them stacked is the shot worth taking.
    private val specs = listOf(
        Spec("Paris Left Bank", City.PARIS, -400.0, -900.0, 520.0, 380.0, 15.0, BLUE, 190, 10, "Weekend trip — Luxembourg gardens and back."),
        Spec("Marais Wander", City.PARIS, 900.0, 300.0, 330.0, 260.0, -20.0, ORCHID, 188, 17),
        Spec("Ciutadella Loop", City.BARCELONA, 1150.0, -550.0, 420.0, 300.0, 40.0, TEAL, 160, 8, "Round the park before work."),
        Spec("Gothic Quarter", City.BARCELONA, 250.0, -1050.0, 380.0, 330.0, 45.0, AMBER, 151, 19),
        Spec("Eixample Grid", City.BARCELONA, -450.0, 0.0, 720.0, 520.0, 45.0, PURPLE, 132, 7, "The long one. 7 km of chamfered corners."),
        Spec("Gràcia Squares", City.BARCELONA, -150.0, 1350.0, 430.0, 380.0, 10.0, RED, 118, 18),
        Spec("Sagrada Família Circuit", City.BARCELONA, 650.0, 650.0, 360.0, 300.0, 45.0, BLUE, 97, 9),
        Spec("Alfama Hills", City.LISBON, 400.0, 0.0, 420.0, 330.0, 30.0, AMBER, 82, 11, "Every street is uphill. Both ways."),
        Spec("Baixa & Chiado", City.LISBON, -350.0, -150.0, 360.0, 300.0, -10.0, TEAL, 81, 16),
        Spec("Sant Antoni Market", City.BARCELONA, -1200.0, -700.0, 340.0, 290.0, 45.0, ORCHID, 64, 8),
        Spec("Poblenou Rambla", City.BARCELONA, 2300.0, -150.0, 560.0, 330.0, 50.0, RED, 45, 7),
        Spec("Beach Promenade", City.BARCELONA, 1700.0, -1350.0, 700.0, 230.0, 48.0, BLUE, 31, 19, "Sunset walk along the sea."),
        Spec("Fort Pienc", City.BARCELONA, 950.0, 150.0, 300.0, 260.0, 45.0, AMBER, 19, 12),
        Spec("Passeig de Gràcia", City.BARCELONA, 100.0, 250.0, 420.0, 300.0, 45.0, TEAL, 9, 18),
        Spec("Raval Evening", City.BARCELONA, -500.0, -900.0, 330.0, 280.0, 30.0, PURPLE, 3, 20, "Quick one after dinner."),
    )

    fun build(nowMs: Long): Set {
        val territories = mutableListOf<Territory>()
        val walks = mutableListOf<Walk>()
        specs.forEachIndexed { i, spec ->
            val random = Random(1_000 + i)
            val ring = loop(spec, random)
            val area = Geo.polygonAreaSqMeters(ring)
            val perimeter = Geo.pathLengthMeters(ring + ring.first())
            val closedAt = timeAgo(nowMs, spec.daysAgo, spec.hour, random)
            // An unhurried pace with stops: ~4.7 km/h moving, a few minutes standing.
            val movingMs = (perimeter / 1.3 * 1_000).toLong()
            val startedAt = closedAt - movingMs - random.nextLong(2, 9) * 60_000
            val id = "demo-%02d".format(i)
            territories += Territory(
                id = id,
                name = spec.name,
                ring = ring,
                polygons = Territory.polygonsFromRing(ring),
                areaSqMeters = area,
                perimeterMeters = perimeter,
                claimedAtEpochMs = closedAt + 40_000,
                colorHex = spec.color,
                notes = spec.notes,
                city = spec.city.cityName,
                country = spec.city.country,
            )
            walks += Walk(
                id = id,
                ring = ring,
                areaSqMeters = area,
                perimeterMeters = perimeter,
                distanceToStartMeters = 8.0 + random.nextDouble() * 14,
                closedAtEpochMs = closedAt,
                startedAtEpochMs = startedAt,
                elevationGainMeters = (if (spec.city == City.LISBON) 70.0 else 12.0) + random.nextDouble() * 30,
                movingMs = movingMs,
                claimed = true,
                onFootMeters = perimeter,
                bridgedMeters = 0.0,
                phoneSteps = (perimeter / 0.74).toInt(),
                // The watch came along from the second Barcelona month on.
                watchSteps = if (spec.daysAgo < 100) (perimeter / 0.76).toInt() else null,
            )
        }

        // One loop closed and left unclaimed, and one walk that couldn't count:
        // both lists have something in them on the profile.
        val unclaimedRing = loop(
            Spec("", City.BARCELONA, -1500.0, 900.0, 260.0, 220.0, 20.0, PURPLE, 12, 13),
            Random(77),
        )
        val unclaimedClosed = timeAgo(nowMs, 12, 13, Random(78))
        walks += Walk(
            id = "demo-unclaimed",
            ring = unclaimedRing,
            areaSqMeters = Geo.polygonAreaSqMeters(unclaimedRing),
            perimeterMeters = Geo.pathLengthMeters(unclaimedRing + unclaimedRing.first()),
            distanceToStartMeters = 11.0,
            closedAtEpochMs = unclaimedClosed,
            startedAtEpochMs = unclaimedClosed - 21 * 60_000,
            elevationGainMeters = 18.0,
            movingMs = 19 * 60_000,
            claimed = false,
            onFootMeters = Geo.pathLengthMeters(unclaimedRing + unclaimedRing.first()),
            bridgedMeters = 0.0,
            phoneSteps = 2_140,
        )

        val tramStart = City.BARCELONA.anchor
        val tram = (0..30).map { k ->
            val t = k / 30.0
            offset(tramStart, east = 2_600 * t + 40 * sin(t * 9), north = -300 * t + 25 * cos(t * 7))
        }
        val tramEnded = timeAgo(nowMs, 26, 9, Random(79))
        val voided = listOf(
            VoidedWalk(
                id = "demo-voided",
                path = tram,
                startedAtEpochMs = tramEnded - 24 * 60_000,
                voidedAtEpochMs = tramEnded,
                distanceMeters = Geo.pathLengthMeters(tram),
                reason = VoidedWalk.Reason.TOO_MUCH_BRIDGED,
                onFootMeters = 700.0,
                bridgedMeters = Geo.pathLengthMeters(tram) - 700.0,
                phoneSteps = 960,
            ),
        )
        return Set(territories, walks, voided)
    }

    /**
     * A loop that reads as walked: an ellipse pushed out of round by a few
     * low harmonics (blocks, a park edge), with GPS-sized jitter on each fix.
     */
    private fun loop(spec: Spec, random: Random): List<LatLng> {
        val points = 48 + random.nextInt(24)
        val harmonics = List(3) { h ->
            Triple(h + 2, 0.06 + random.nextDouble() * 0.10, random.nextDouble() * 2 * PI)
        }
        val rotation = spec.rotationDeg * PI / 180
        return (0 until points).map { k ->
            val t = 2 * PI * k / points
            val wobble = 1 + harmonics.sumOf { (n, amp, phase) -> amp * sin(n * t + phase) }
            val x = spec.rx * cos(t) * wobble + random.nextDouble(-4.0, 4.0)
            val y = spec.ry * sin(t) * wobble + random.nextDouble(-4.0, 4.0)
            offset(
                spec.city.anchor,
                east = spec.east + x * cos(rotation) - y * sin(rotation),
                north = spec.north + x * sin(rotation) + y * cos(rotation),
            )
        }
    }

    private fun offset(from: LatLng, east: Double, north: Double): LatLng = LatLng(
        lat = from.lat + north / METERS_PER_DEGREE,
        lng = from.lng + east / (METERS_PER_DEGREE * cos(from.lat * PI / 180)),
    )

    /** [days] before [nowMs], at about [hour] o'clock UTC-ish — near enough for a demo. */
    private fun timeAgo(nowMs: Long, days: Int, hour: Int, random: Random): Long {
        val dayStart = nowMs - nowMs % DAY_MS - days * DAY_MS
        return dayStart + hour * HOUR_MS + random.nextLong(0, 50) * 60_000
    }

    private const val METERS_PER_DEGREE = 111_320.0
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
}
