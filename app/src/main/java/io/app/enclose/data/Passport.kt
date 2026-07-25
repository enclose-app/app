package io.app.enclose.data

/** One country the walker has taken ground in. */
data class CountryStamp(
    val country: String,
    /** Distinct named cities claimed in this country, alphabetical. */
    val cities: List<String>,
    val territoryCount: Int,
    val claimedAreaSqMeters: Double,
    /** When the first claim here was made — the date on the stamp. */
    val firstClaimedAtEpochMs: Long,
)

/**
 * Countries walked, as passport stamps.
 *
 * Ordered by when each country was *first* claimed in, oldest first, because a
 * passport is a record of travel rather than a leaderboard — reordering it by
 * area would lose the one thing the dates are for.
 *
 * Unlike [Coverage], there is no percentage here. Claimed area as a share of a
 * country is a meaninglessly small number, and rendering "0%" next to a country
 * you walked across would read as failure rather than as a stamp.
 *
 * Pure and free of Android and DB types.
 */
object Passport {

    /** Stamps for every country with at least one named claim, oldest first. */
    fun stamps(territories: List<Territory>): List<CountryStamp> =
        territories
            // A stamp needs a country. Claims whose lookup hasn't resolved yet
            // are simply absent rather than collected under a placeholder — an
            // "Unknown" stamp is not a place anyone has been.
            .filter { it.country.isNotBlank() }
            .groupBy { it.country.trim() }
            .map { (country, claims) -> stampFor(country, claims) }
            .sortedBy { it.firstClaimedAtEpochMs }

    private fun stampFor(country: String, claims: List<Territory>) = CountryStamp(
        country = country,
        cities = claims
            .map { it.city.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted(),
        territoryCount = claims.size,
        claimedAreaSqMeters = claims.sumOf { it.areaSqMeters },
        firstClaimedAtEpochMs = claims.minOf { it.claimedAtEpochMs },
    )
}
