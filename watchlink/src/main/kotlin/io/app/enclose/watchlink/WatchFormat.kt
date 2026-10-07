package io.app.enclose.watchlink

import java.util.Locale

/**
 * The watch's figures that carry no words. Anything with a unit in it is the
 * watch app's to word, from its string resources (`wear/.../Units.kt`), since
 * this module has no resources and a unit is text a translator needs to reach.
 */
object WatchFormat {

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
