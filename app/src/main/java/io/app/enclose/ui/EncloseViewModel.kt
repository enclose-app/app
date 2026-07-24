package io.app.enclose.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
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

class EncloseViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as EncloseApp).repository
    private val walkRepository = (app as EncloseApp).walkRepository

    init {
        // Persist EVERY successful closed loop the moment it closes — offline,
        // in local SQLite — whether or not the user goes on to claim it.
        viewModelScope.launch {
            TrackingManager.pendingClaim.collect { pending ->
                if (pending != null) walkRepository.saveClosed(pending.toWalk(claimed = false))
            }
        }
    }

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
        // Share the id with the persisted walk so the two are linked.
        val territory = Territory(
            id = pending.id,
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
            // Mark the already-recorded walk as claimed (race-safe upsert).
            walkRepository.saveClaimed(pending.toWalk(claimed = true))
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
        // Test walks use relaxed thresholds so a tap-built loop can close.
        TrackingManager.startWalk(relaxedThresholds = _testMode.value)
        // In test mode we feed points from map taps, so skip the GPS service.
        if (!_testMode.value) LocationService.start(getApplication())
    }

    fun stopWalk() {
        if (!_testMode.value) LocationService.stop(getApplication())
        // Claims the loop if it's ready to close; otherwise abandons the walk.
        TrackingManager.finishWalk()
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

    fun renameTerritory(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        val territory = territories.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            // Name changed → re-sync it.
            repository.claim(territory.copy(name = name, syncStatus = SyncStatus.PENDING))
            SyncScheduler.requestSync(getApplication())
        }
    }

    fun deleteTerritory(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    private fun TrackingManager.PendingClaim.toWalk(claimed: Boolean) = Walk(
        id = id,
        ring = ring,
        areaSqMeters = areaSqMeters,
        perimeterMeters = perimeterMeters,
        distanceToStartMeters = distanceToStartMeters,
        closedAtEpochMs = closedAtEpochMs,
        claimed = claimed,
        syncStatus = SyncStatus.PENDING,
    )
}
