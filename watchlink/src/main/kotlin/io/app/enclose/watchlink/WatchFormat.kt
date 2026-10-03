package io.app.enclose.watchlink

import java.util.Locale

/**
 * The watch's figures, worded exactly as the phone words them (see the phone's
 * `ui/Format.kt`) so the same walk doesn't read two ways on two screens.
 */
object WatchFormat {

    /** "1.24 km" past a km, otherwise whole "840 m". */
    fun distance(meters: Int): String =
        if (meters >= 1000) String.format(Locale.US, "%.2f km", meters / 1000.0)
        else "$meters m"

    /** "0.05 km²" from a square kilometre up, otherwise whole "51235 m²". */
    fun area(sqMeters: Int): String =
        if (sqMeters >= 1_000_000) String.format(Locale.US, "%.2f km²", sqMeters / 1_000_000.0)
        else "$sqMeters m²"

    /** mm:ss, or h:mm:ss once past an hour. Negative (clock skew) reads as 0:00. */
    fun elapsed(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
