package io.app.enclose.wear

import io.app.enclose.watchlink.GeoPoint
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchMap
import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchStatus.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A made-up walk for looking at the watch screens with no phone paired — an
 * emulator, or screenshots for the Play listing. Debug builds only: see
 * [MainActivity], which shows it for `adb shell am start ... --ez demo true`.
 *
 * Near the emulator's default location, so the map has streets under it.
 */
object DemoWalk {
    private val start = GeoPoint(37.42200, -122.08410)

    /** A lopsided loop of ~1.1 km, stopping just short of where it began. */
    private val path: List<GeoPoint> = (0..40).map { i ->
        val t = 2 * PI * i / 40 * 0.995
        GeoPoint(
            start.lat + 0.0019 * sin(t) * (1 + 0.25 * cos(3 * t)),
            start.lng + 0.0028 * (1 - cos(t)),
        )
    }

    fun status(nowMs: Long) = WatchStatus(
        phase = Phase.WALKING,
        startedAtEpochMs = nowMs - 14 * 60_000L - 23_000L,
        distanceMeters = 1_140,
        toStartMeters = 5,
        canCloseLoop = true,
        readyToClose = true,
    )

    fun map(startedAtEpochMs: Long?) = WatchMap(
        startedAtEpochMs = startedAtEpochMs,
        path = path,
        start = start,
        current = path.last(),
        closeRadiusMeters = 10.0,
    )

    val claims = WatchClaims(
        listOf(
            claim(0xFF1E88A8.toInt(), GeoPoint(37.4235, -122.0868), 0.0007),
            claim(0xFF7B1FA2.toInt(), GeoPoint(37.4203, -122.0790), 0.0005),
        ),
    )

    private fun claim(color: Int, c: GeoPoint, half: Double) = WatchClaims.Claim(
        color = color,
        polygons = listOf(
            listOf(
                listOf(
                    GeoPoint(c.lat - half, c.lng - half * 1.3),
                    GeoPoint(c.lat - half * 0.8, c.lng + half * 1.3),
                    GeoPoint(c.lat + half, c.lng + half),
                    GeoPoint(c.lat + half * 0.7, c.lng - half * 1.2),
                ),
            ),
        ),
    )
}
