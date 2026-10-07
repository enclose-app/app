package io.app.enclose.wear

import android.content.res.Resources

/**
 * The watch's figures with their units, from strings_watch.xml so both the unit
 * and the number follow the watch's language. Worded as the phone's
 * `ui/Format.kt` words them. In Compose, take the receiver from
 * `LocalResources.current`.
 */

/** "1.24 km" past a km, otherwise whole "840 m". */
internal fun Resources.formatDistance(meters: Int): String =
    if (meters >= 1000) getString(R.string.unit_distance_km, meters / 1000.0)
    else getString(R.string.unit_distance_m, meters)

/** "0.05 km²" from a square kilometre up, otherwise whole "51235 m²". */
internal fun Resources.formatArea(sqMeters: Int): String =
    if (sqMeters >= 1_000_000) getString(R.string.unit_area_km2, sqMeters / 1_000_000.0)
    else getString(R.string.unit_area_m2, sqMeters)
