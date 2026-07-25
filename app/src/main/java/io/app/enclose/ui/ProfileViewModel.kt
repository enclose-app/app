package io.app.enclose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.data.Profile
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
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

    val state: StateFlow<ProfileUiState> =
        combine(
            profileRepository.profile,
            repository.territories,
            walkRepository.walks,
        ) { profile, territories, walks ->
            ProfileUiState(
                profile = profile,
                stats = computeStats(territories, walks),
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
            cityCoveragePercent = cityCoverage(territories, totalArea),
        )
    }

    /**
     * "% of your city explored".
     *
     * "City" is undefined, so we use a concrete, self-explanatory proxy:
     * coverage of your OWN claimed region. We take the geographic bounding box
     * of every claim's points (min/max lat/lng, projected to meters at the mean
     * latitude) as the "region you've been active in", and report claimed area
     * as a fraction of that box. It answers "how densely have I filled in the
     * area I roam?" and is always between 0 and 100%. Guarded against empty
     * input and a zero-area box (single point / colinear claims).
     */
    private fun cityCoverage(territories: List<Territory>, totalArea: Double): Double {
        val points: List<LatLng> = territories.flatMap { it.ring }
        if (points.size < 3 || totalArea <= 0.0) return 0.0

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        // Bounding box as a closed ring, area via the same projection as claims.
        val boxRing = listOf(
            LatLng(minLat, minLng),
            LatLng(minLat, maxLng),
            LatLng(maxLat, maxLng),
            LatLng(maxLat, minLng),
        )
        val boxArea = Geo.polygonAreaSqMeters(boxRing)
        if (boxArea <= 0.0) return 0.0

        return (totalArea / boxArea * 100.0).coerceIn(0.0, 100.0)
    }
}

data class ProfileUiState(
    val profile: Profile? = null,
    val stats: ProfileStats = ProfileStats(),
    val loading: Boolean = true,
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
    /** Coverage of your claimed region's bounding box; see [ProfileViewModel]. */
    val cityCoveragePercent: Double = 0.0,
)
