package io.app.enclose.ui

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.app.enclose.R
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
 * and TerritoryDetailScreen. Anything with a unit or a word in it is an
 * extension on [Resources] and formats through strings_units.xml, so both the
 * unit and the number (decimal separator, digit grouping) follow the device's
 * language — in Compose, take the receiver from `LocalResources.current`.
 * Elapsed time and colours have no words in them and need no receiver.
 */

/** Area as "1.24 km²" past a km², otherwise whole "840 m²". */
internal fun Resources.formatArea(sqMeters: Double): String =
    if (sqMeters >= 1_000_000) getString(R.string.unit_area_km2, sqMeters / 1_000_000.0)
    else getString(R.string.unit_area_m2, sqMeters.roundToInt())

/**
 * A coordinate as "37.98380° N, 23.72750° E". Five decimals is about a metre —
 * past the point where more digits say anything a GPS fix can back up.
 */
internal fun Resources.formatCoordinates(point: LatLng): String {
    val lat = getString(
        if (point.lat >= 0) R.string.coord_north else R.string.coord_south,
        abs(point.lat),
    )
    val lng = getString(
        if (point.lng >= 0) R.string.coord_east else R.string.coord_west,
        abs(point.lng),
    )
    return getString(R.string.coord_pair, lat, lng)
}

/** Distance as "1.24 km" past a km, otherwise whole "840 m". */
internal fun Resources.formatDistance(meters: Double): String =
    if (meters >= 1000) getString(R.string.unit_distance_km, meters / 1000.0)
    else getString(R.string.unit_distance_m, meters.roundToInt())

/**
 * Climb as whole metres — "48 m", "1,240 m" — never converted to kilometres.
 * Elevation gain is read vertically and compared against other climbs, and
 * "1.24 km" of ascent reads like a horizontal distance; the grouped metres also
 * stop a long day being mistaken for a short one at a glance.
 */
internal fun Resources.formatClimb(meters: Double): String =
    getString(R.string.unit_climb, meters.roundToInt())

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
internal fun Resources.formatPace(distanceMeters: Double, elapsedMs: Long): String {
    val km = distanceMeters / 1000.0
    if (km < 0.01 || elapsedMs <= 0) return EM_DASH
    val secPerKm = (elapsedMs / 1000.0) / km
    if (secPerKm.isInfinite() || secPerKm > 5940) return EM_DASH // cap at 99:00
    val m = (secPerKm / 60).toInt()
    val s = (secPerKm % 60).roundToInt()
    // Handle rounding 60 → next minute.
    val (mm, ss) = if (s == 60) (m + 1) to 0 else m to s
    return getString(R.string.unit_pace, mm, ss)
}

/**
 * A file size as "812 KB" / "4.3 MB". Whole kilobytes but one decimal on
 * megabytes, because the number people compare a backup against is how much room
 * they have, and "4 MB" and "4.9 MB" are not the same answer to that.
 */
internal fun Resources.formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> getString(R.string.unit_file_mb, bytes / 1_000_000.0)
    bytes >= 1_000 -> getString(R.string.unit_file_kb, (bytes / 1_000.0).roundToInt())
    else -> getQuantityString(R.plurals.unit_file_bytes, bytes.toInt(), bytes.toInt())
}

/**
 * Dates and times use the locale's own pattern for the fields asked for rather
 * than a fixed English one: "Oct 7, 2026" here is "7 Οκτ 2026" in Greek, and
 * the order of day, month and year is the locale's to decide. Hours stay 24h,
 * as they always were.
 */
private fun localPattern(skeleton: String): SimpleDateFormat {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

internal fun Resources.formatDate(epochMs: Long): String =
    getString(
        R.string.date_and_time,
        formatDay(epochMs),
        localPattern("HHmm").format(Date(epochMs)),
    )

internal fun formatDay(epochMs: Long): String =
    localPattern("yyyyMMMd").format(Date(epochMs))

/**
 * Human-friendly recency for list rows: "Today 14:32", "Yesterday 09:05",
 * "4 days ago", then an absolute date. Reading "3 days ago" in a list is far
 * faster than parsing a timestamp on every row.
 */
internal fun Resources.formatRelativeDay(
    epochMs: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val time = localPattern("HHmm").format(Date(epochMs))
    return when (val days = calendarDaysAgo(epochMs, now)) {
        0 -> getString(R.string.day_today, time)
        1 -> getString(R.string.day_yesterday, time)
        in 2..6 -> getQuantityString(R.plurals.days_ago, days, days)
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

internal fun Resources.syncLabel(status: SyncStatus): String =
    getString(if (status == SyncStatus.SYNCED) R.string.sync_synced else R.string.sync_not_synced)

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
