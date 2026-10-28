package io.app.enclose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.data.CityCoverage
import io.app.enclose.data.CountryStamp
import io.app.enclose.data.Coverage
import io.app.enclose.data.Footprint
import io.app.enclose.data.Passport
import io.app.enclose.data.Profile
import io.app.enclose.data.Territory
import io.app.enclose.data.VoidedWalk
import io.app.enclose.data.Walk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    private val voidedWalkRepository = (app as EncloseApp).voidedWalkRepository
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
            voidedWalkRepository.voidedWalks,
        ) { profile, territories, walks, voided ->
            ProfileUiState(
                profile = profile,
                stats = computeStats(territories, walks),
                voided = voided,
                loading = false,
            )
        }
            // The totals measure shared ground with JTS (Footprint), which is far
            // too slow for the main thread once there are a few dozen claims.
            .flowOn(Dispatchers.Default)
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState(),
        )

    /** Only from the list that shows it, behind a confirmation. */
    fun deleteVoided(id: String) {
        viewModelScope.launch { voidedWalkRepository.delete(id) }
    }

    fun updateName(first: String, last: String) {
        if (first.isBlank() && last.isBlank()) return
        viewModelScope.launch { profileRepository.updateName(first, last) }
    }

    fun regenerateName() {
        viewModelScope.launch { profileRepository.regenerate() }
    }

    private fun computeStats(territories: List<Territory>, walks: List<Walk>): ProfileStats {
        // Claims are layers and can overlap, so ground walked over twice is
        // counted once rather than added up.
        val totalArea = Footprint.areaSqMeters(territories)
        val totalDistance = walks.sumOf { it.perimeterMeters }
        val totalClimb = walks.sumOf { it.elevationGainMeters }
        val biggest = territories.maxByOrNull { it.areaSqMeters }
        val longestWalk = walks.maxByOrNull { it.perimeterMeters }
        val biggestClimb = walks.maxOfOrNull { it.elevationGainMeters } ?: 0.0
        val firstClaim = territories.minOfOrNull { it.claimedAtEpochMs }

        return ProfileStats(
            territoryCount = territories.size,
            walkCount = walks.size,
            totalAreaSqMeters = totalArea,
            totalDistanceMeters = totalDistance,
            totalElevationGainMeters = totalClimb,
            biggestClimbMeters = biggestClimb,
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
    /** Walks the anti-cheat ended, most recent first. */
    val voided: List<VoidedWalk> = emptyList(),
    val loading: Boolean = true,
)

data class ProfileStats(
    val territoryCount: Int = 0,
    val walkCount: Int = 0,
    /** Ground covered by every claim, shared ground counted once — see [Footprint]. */
    val totalAreaSqMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    /**
     * Climb summed across every recorded walk. Walks made before altitude was
     * kept contribute zero, so this can only understate — never invent — height.
     */
    val totalElevationGainMeters: Double = 0.0,
    val biggestTerritoryName: String? = null,
    val biggestTerritoryAreaSqMeters: Double = 0.0,
    val longestWalkMeters: Double = 0.0,
    /** Most climbed on a single walk; zero when nothing has any altitude yet. */
    val biggestClimbMeters: Double = 0.0,
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
