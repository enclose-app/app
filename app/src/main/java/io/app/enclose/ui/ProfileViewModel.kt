package io.app.enclose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.data.CityCoverage
import io.app.enclose.data.CountryStamp
import io.app.enclose.data.Coverage
import io.app.enclose.data.Passport
import io.app.enclose.data.Profile
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the offline profile / stats screen. Kept separate from
 * [EncloseViewModel] so the two can be edited independently. All figures are
 * derived locally from SQLite (territories + walks + profile).
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val profileRepository = (app as EncloseApp).profileRepository
    private val repository = (app as EncloseApp).repository
    private val walkRepository = (app as EncloseApp).walkRepository
    private val cityTagger = (app as EncloseApp).cityTagger

    init {
        // Catch up on claims with no city yet — ones walked offline, or made
        // before claims were placed at all. Cities are shown on this screen, so
        // opening it is exactly when it's worth spending the lookups.
        viewModelScope.launch { cityTagger.backfill() }
    }

    val state: StateFlow<ProfileUiState> =
        combine(
            profileRepository.profile,
            repository.territories,
            walkRepository.walks,
            repository.conquered,
        ) { profile, territories, walks, conquered ->
            ProfileUiState(
                profile = profile,
                stats = computeStats(territories, walks),
                fallen = fallenClaims(conquered, territories),
                loading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState(),
        )

    fun updateName(first: String, last: String) {
        if (first.isBlank() && last.isBlank()) return
        viewModelScope.launch { profileRepository.updateName(first, last) }
    }

    fun regenerateName() {
        viewModelScope.launch { profileRepository.regenerate() }
    }

    /**
     * Conquered claims, resolved into something displayable. The claim that took
     * each one may itself have fallen since, so names are looked up across both
     * lists — a chain of absorptions still reads correctly.
     */
    private fun fallenClaims(
        conquered: List<Territory>,
        active: List<Territory>,
    ): List<FallenClaim> {
        val namesById = (active + conquered).associate { it.id to it.name }
        return conquered.map { territory ->
            FallenClaim(
                id = territory.id,
                name = territory.name,
                areaSqMeters = territory.areaSqMeters,
                conqueredAtEpochMs = territory.conqueredAtEpochMs ?: 0L,
                takenByName = territory.conqueredById?.let { namesById[it] },
            )
        }
    }

    private fun computeStats(territories: List<Territory>, walks: List<Walk>): ProfileStats {
        val totalArea = territories.sumOf { it.areaSqMeters }
        val totalDistance = walks.sumOf { it.perimeterMeters }
        val biggest = territories.maxByOrNull { it.areaSqMeters }
        val longestWalk = walks.maxByOrNull { it.perimeterMeters }
        val firstClaim = territories.minOfOrNull { it.claimedAtEpochMs }

        return ProfileStats(
            territoryCount = territories.size,
            walkCount = walks.size,
            totalAreaSqMeters = totalArea,
            totalDistanceMeters = totalDistance,
            biggestTerritoryName = biggest?.name,
            biggestTerritoryAreaSqMeters = biggest?.areaSqMeters ?: 0.0,
            longestWalkMeters = longestWalk?.perimeterMeters ?: 0.0,
            firstClaimEpochMs = firstClaim,
            cities = Coverage.byCity(territories),
            stamps = Passport.stamps(territories),
        )
    }
}

data class ProfileUiState(
    val profile: Profile? = null,
    val stats: ProfileStats = ProfileStats(),
    /** Claims a later walk swallowed whole, most recently fallen first. */
    val fallen: List<FallenClaim> = emptyList(),
    val loading: Boolean = true,
)

/** A territory that was absorbed by a later claim, ready to display. */
data class FallenClaim(
    val id: String,
    val name: String,
    /** The area it held at the moment it fell. */
    val areaSqMeters: Double,
    val conqueredAtEpochMs: Long,
    /** Name of the claim that took it, if that claim is still on record. */
    val takenByName: String?,
)

data class ProfileStats(
    val territoryCount: Int = 0,
    val walkCount: Int = 0,
    val totalAreaSqMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val biggestTerritoryName: String? = null,
    val biggestTerritoryAreaSqMeters: Double = 0.0,
    val longestWalkMeters: Double = 0.0,
    val firstClaimEpochMs: Long? = null,
    /** Per-city coverage, biggest first. See [Coverage] for what the % means. */
    val cities: List<CityCoverage> = emptyList(),
    /** Countries walked, oldest stamp first. See [Passport]. */
    val stamps: List<CountryStamp> = emptyList(),
) {
    /**
     * The city the walker has taken the most ground in — their home turf.
     * Prefers a named city over the unplaced group even when the unplaced
     * claims are larger: a headline that reads "Unplaced claims" tells the user
     * nothing while a real city name is sitting right underneath it.
     */
    val topCity: CityCoverage?
        get() = cities.firstOrNull { !it.isUnknown } ?: cities.firstOrNull()
}
