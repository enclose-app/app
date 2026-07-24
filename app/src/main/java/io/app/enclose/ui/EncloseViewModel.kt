package io.app.enclose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.geo.Geo
import io.app.enclose.geo.GeoClip
import io.app.enclose.geo.LatLng
import io.app.enclose.sync.SyncScheduler
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.TrackingManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class EncloseViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as EncloseApp).repository

    /** Live walk state (path, distance, whether the loop can close). */
    val walk: StateFlow<TrackingManager.WalkState> = TrackingManager.walk

    /** All claimed territories, straight from SQLite. */
    val territories: StateFlow<List<Territory>> =
        repository.territories.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** One-shot "you claimed a territory!" events for the UI to celebrate. */
    private val _claimEvents = MutableSharedFlow<Territory>(extraBufferCapacity = 4)
    val claimEvents = _claimEvents.asSharedFlow()

    /** Test mode: tap the map to inject points instead of walking with GPS. */
    private val _testMode = MutableStateFlow(false)
    val testMode: StateFlow<Boolean> = _testMode.asStateFlow()

    /** A closed loop awaiting the user's claim decision (drives the modal). */
    val pendingClaim: StateFlow<TrackingManager.PendingClaim?> = TrackingManager.pendingClaim

    /** User confirmed the modal: persist with their chosen name/color and sync. */
    fun confirmClaim(name: String, colorHex: String) {
        val pending = TrackingManager.pendingClaim.value ?: return
        val newRing = pending.ring
        val territory = Territory(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { pending.suggestedName },
            ring = newRing,
            polygons = Territory.polygonsFromRing(newRing),
            areaSqMeters = pending.areaSqMeters,
            perimeterMeters = pending.perimeterMeters,
            claimedAtEpochMs = System.currentTimeMillis(),
            colorHex = colorHex,
            syncStatus = SyncStatus.PENDING,
        )
        val existing = territories.value
        TrackingManager.clearPending()
        viewModelScope.launch {
            // The new claim conquers overlapping land: carve it out of older claims.
            for (other in existing) {
                if (!GeoClip.overlaps(other.polygons, newRing)) continue
                val reduced = GeoClip.subtract(other.polygons, newRing)
                if (reduced.isEmpty()) {
                    repository.delete(other.id)
                } else {
                    repository.claim(
                        other.copy(
                            polygons = reduced,
                            areaSqMeters = Geo.areaOfPolygons(reduced),
                            syncStatus = SyncStatus.PENDING,
                        ),
                    )
                }
            }
            repository.claim(territory)
            SyncScheduler.requestSync(getApplication())
            _claimEvents.tryEmit(territory)
        }
    }

    /** User dismissed the modal without claiming. */
    fun discardClaim() = TrackingManager.clearPending()

    fun startWalk() {
        TrackingManager.startWalk()
        // In test mode we feed points from map taps, so skip the GPS service.
        if (!_testMode.value) LocationService.start(getApplication())
    }

    fun stopWalk() {
        if (!_testMode.value) LocationService.stop(getApplication())
        TrackingManager.cancelWalk()
    }

    fun setTestMode(enabled: Boolean) {
        _testMode.value = enabled
        // Leaving test mode abandons any tap-built walk in progress.
        if (!enabled && walk.value.isTracking) TrackingManager.cancelWalk()
    }

    /** Inject a tapped point. The first tap auto-starts a (serviceless) walk. */
    fun addTestPoint(point: LatLng) {
        if (!_testMode.value) return
        if (!walk.value.isTracking) startWalk()
        TrackingManager.onLocation(point)
    }

    fun deleteTerritory(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
