package io.app.enclose.geo

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a coordinate is, as far as the device can say.
 *
 * Every field is nullable and independently so: the platform geocoder routinely
 * names a country but not a city (open country, small villages, patchy data),
 * and callers are expected to show what resolved and leave out what didn't
 * rather than print a placeholder.
 */
data class Place(
    /** The city proper ([Address.locality]). */
    val city: String? = null,
    /** County, region or state — whatever sits between city and country. */
    val area: String? = null,
    val country: String? = null,
    /** ISO country code, e.g. "GR". */
    val countryCode: String? = null,
) {
    /** True when the lookup produced no usable name at all. */
    val isEmpty: Boolean get() = city == null && area == null && country == null

    /**
     * The single name a claim is filed under. Falls back down the hierarchy so
     * a claim in open country is grouped under its region or country rather
     * than dropping out of the per-city breakdown entirely.
     */
    val groupingName: String? get() = city ?: area ?: country
}

/**
 * Turns a coordinate into a city name, so claims can be grouped by where they
 * were walked.
 *
 * Uses the platform [Geocoder]: no API key, no extra dependency, and it honours
 * the device locale. Like everything else that isn't the walk itself, it
 * degrades quietly — no geocoder on the device, no network, or a coordinate in
 * the middle of nowhere all yield null, the claim keeps a blank city, and the
 * caller can try again later. Claiming never waits on this.
 *
 * Results are memoised on a coarse (~100 m) grid, which collapses the repeated
 * lookups a backfill of many claims in one neighbourhood would otherwise make.
 */
class CityResolver(context: Context) {

    private val appContext = context.applicationContext

    /** Null when the device has no geocoding backend at all. */
    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(appContext, Locale.getDefault()) else null

    private val cache = mutableMapOf<String, Place>()

    /** True when reverse geocoding can be attempted at all on this device. */
    val isAvailable: Boolean get() = geocoder != null

    /**
     * The city containing [point], or null if it can't be determined right now.
     * Safe to call from any coroutine; the platform call is asynchronous.
     */
    suspend fun resolve(point: LatLng): String? = resolvePlace(point)?.groupingName

    /**
     * Everything the geocoder can say about [point] — city, area, country — or
     * null when it can say nothing (no geocoder, no network, or a coordinate it
     * doesn't recognise). Individual fields go missing far more often than the
     * whole lookup fails, so read them rather than assuming a non-null result
     * is complete.
     */
    suspend fun resolvePlace(point: LatLng): Place? {
        val geocoder = geocoder ?: return null
        val key = cacheKey(point)
        cache[key]?.let { return it }

        // A geocoder that never calls back would otherwise hang here until the
        // caller's scope is cancelled, stalling the whole backfill behind it.
        val address = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            runCatching { firstAddress(geocoder, point) }.getOrNull()
        } ?: return null

        val place = placeOf(address)
        // An address that names nothing is worth no more than a failure, and
        // caching it would stop a later, better-connected lookup from trying.
        if (place.isEmpty) return null
        cache[key] = place
        return place
    }

    private suspend fun firstAddress(geocoder: Geocoder, point: LatLng): Address? =
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                point.lat,
                point.lng,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    // Nothing to report to the user: a missing city name is not
                    // something they did, and the claim itself is unaffected.
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    /**
     * Split an address into the three levels worth showing. Blank strings are
     * normalised to null so callers only have to check one thing, and
     * [Place.groupingName] then reproduces the old city-with-fallbacks rule
     * exactly: locality, else sub-admin area, else admin area, else country.
     */
    private fun placeOf(address: Address): Place = Place(
        city = address.locality.orNull(),
        area = address.subAdminArea.orNull() ?: address.adminArea.orNull(),
        country = address.countryName.orNull(),
        countryCode = address.countryCode.orNull(),
    )

    private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }

    /** ~3 decimal places ≈ 100 m: fine enough that it can't cross a city line. */
    private fun cacheKey(point: LatLng): String =
        String.format(Locale.US, "%.3f,%.3f", point.lat, point.lng)

    private companion object {
        /**
         * Generous for a network round trip, short enough that a backfill of
         * several claims can't sit unresponsive. A miss isn't costly — the claim
         * keeps a blank city and is retried next time.
         */
        const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}
