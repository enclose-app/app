package io.app.enclose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every user-facing string formatter, in one place.
 *
 * These used to be duplicated (and drifting) across MapScreen, ProfileScreen
 * and TerritoryDetailScreen. Numeric formats are pinned to [Locale.US] so the
 * decimal separator matches the "km"/"m²" units we hard-code alongside them,
 * while dates deliberately follow the device locale.
 */

/** Area as "1.24 km²" past a km², otherwise whole "840 m²". */
internal fun formatArea(sqMeters: Double): String =
    if (sqMeters >= 1_000_000) String.format(Locale.US, "%.2f km²", sqMeters / 1_000_000.0)
    else "${sqMeters.roundToInt()} m²"

/**
 * A coordinate as "37.98380° N, 23.72750° E". Five decimals is about a metre —
 * past the point where more digits say anything a GPS fix can back up.
 */
internal fun formatCoordinates(point: LatLng): String {
    val lat = String.format(Locale.US, "%.5f° %s", abs(point.lat), if (point.lat >= 0) "N" else "S")
    val lng = String.format(Locale.US, "%.5f° %s", abs(point.lng), if (point.lng >= 0) "E" else "W")
    return "$lat, $lng"
}

/** Distance as "1.24 km" past a km, otherwise whole "840 m". */
internal fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.2f km", meters / 1000.0)
    else "${meters.roundToInt()} m"

/** Elapsed time as mm:ss, or h:mm:ss once past an hour. */
internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Average pace as "m:ss /km"; "—" until there's meaningful distance. */
internal fun formatPace(distanceMeters: Double, elapsedMs: Long): String {
    val km = distanceMeters / 1000.0
    if (km < 0.01 || elapsedMs <= 0) return EM_DASH
    val secPerKm = (elapsedMs / 1000.0) / km
    if (secPerKm.isInfinite() || secPerKm > 5940) return EM_DASH // cap at 99:00
    val m = (secPerKm / 60).toInt()
    val s = (secPerKm % 60).roundToInt()
    // Handle rounding 60 → next minute.
    val (mm, ss) = if (s == 60) (m + 1) to 0 else m to s
    return String.format(Locale.US, "%d:%02d /km", mm, ss)
}

internal fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(epochMs))

internal fun formatDay(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

/**
 * Human-friendly recency for list rows: "Today 14:32", "Yesterday 09:05",
 * "4 days ago", then an absolute date. Reading "3 days ago" in a list is far
 * faster than parsing a timestamp on every row.
 */
internal fun formatRelativeDay(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    return when (val days = calendarDaysAgo(epochMs, now)) {
        0 -> "Today $time"
        1 -> "Yesterday $time"
        in 2..6 -> "$days days ago"
        else -> formatDay(epochMs)
    }
}

/** Whole calendar days between the two instants (not 24h blocks). */
private fun calendarDaysAgo(epochMs: Long, now: Long): Int {
    if (epochMs > now) return 0
    fun midnight(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diff = midnight(now) - midnight(epochMs)
    return TimeUnit.MILLISECONDS.toDays(diff).toInt()
}

internal fun syncLabel(status: SyncStatus): String =
    if (status == SyncStatus.SYNCED) "Synced" else "Not synced"

/** Placeholder for "no value yet", so every screen uses the same glyph. */
internal const val EM_DASH = "—"

/** Palette offered when claiming or recoloring a territory. */
internal val CLAIM_PALETTE = listOf(
    "#7B1FA2", // purple (brand)
    "#AB47BC", // orchid
    "#1E88E5", // blue
    "#F2A65A", // amber
    "#E53935", // red
    "#00897B", // teal
)

/**
 * Parse a stored hex color, falling back to the brand purple instead of
 * throwing. Colors come out of the database, so a hand-edited or
 * future-version row must not be able to crash the list.
 */
internal fun hexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrElse { Color(android.graphics.Color.parseColor(Territory.DEFAULT_COLOR)) }

/** "#RRGGBB" for handing Compose colors to MapLibre's string-based style API. */
internal fun Color.toHexString(): String =
    String.format(Locale.US, "#%06X", 0xFFFFFF and toArgb())
