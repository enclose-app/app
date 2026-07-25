package io.app.enclose.ui

import android.app.Application
import android.content.Context
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
import io.app.enclose.tracking.ActivityType
import io.app.enclose.tracking.VoidReason
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

    /** Small bag of UI-only preferences (what the user has already been shown). */
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Persist EVERY successful closed loop the moment it closes — offline,
        // in local SQLite — whether or not the user goes on to claim it.
        viewModelScope.launch {
            TrackingManager.pendingClaim.collect { pending ->
                if (pending != null) walkRepository.saveClosed(pending.toWalk(claimed = false))
            }
        }
        // A walk voided for vehicle movement: shut the GPS service down (the
        // manager can't, by design) and hand the reason to the UI to explain.
        viewModelScope.launch {
            TrackingManager.voidEvents.collect { reason ->
                LocationService.stop(getApplication())
                _voidedWalk.value = reason
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

    /**
     * How the user is getting around, chosen before starting. Remembered between
     * sessions: most people do the same thing most days, so starting stays a
     * single tap. It tightens the motion checks — see [ActivityType].
     */
    private val _activityType = MutableStateFlow(
        runCatching { ActivityType.valueOf(prefs.getString(KEY_ACTIVITY, null) ?: "") }
            .getOrDefault(ActivityType.WALK),
    )
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    fun setActivityType(type: ActivityType) {
        _activityType.value = type
        prefs.edit().putString(KEY_ACTIVITY, type.name).apply()
    }

    /**
     * Which basemap the map draws. Follows the system theme until the user picks
     * a side with the map's own toggle, then stays put — legibility outdoors is a
     * separate concern from whether they want a dark app.
     */
    private val _basemapStyle = MutableStateFlow(
        runCatching { BasemapStyle.valueOf(prefs.getString(KEY_BASEMAP, null) ?: "") }
            .getOrDefault(BasemapStyle.SYSTEM),
    )
    val basemapStyle: StateFlow<BasemapStyle> = _basemapStyle.asStateFlow()

    fun setBasemapStyle(style: BasemapStyle) {
        _basemapStyle.value = style
        prefs.edit().putString(KEY_BASEMAP, style.name).apply()
    }

    /** Test mode: tap the map to inject points instead of walking with GPS. */
    private val _testMode = MutableStateFlow(false)
    val testMode: StateFlow<Boolean> = _testMode.asStateFlow()

    /**
     * Set when a walk was discarded because the movement wasn't human-powered.
     * The UI shows an explanation and calls [dismissVoidedWalk].
     */
    private val _voidedWalk = MutableStateFlow<VoidReason?>(null)
    val voidedWalk: StateFlow<VoidReason?> = _voidedWalk.asStateFlow()

    fun dismissVoidedWalk() {
        _voidedWalk.value = null
    }

    /** A closed loop awaiting the user's claim decision (drives the modal). */
    val pendingClaim: StateFlow<TrackingManager.PendingClaim?> = TrackingManager.pendingClaim

    /**
     * Whether to show the "how it works" explainer. Opens automatically on first
     * launch — the walk-a-loop-to-claim-it mechanic isn't discoverable from a map
     * with a Start button — and is reachable from the map menu afterwards.
     */
    private val _showHowItWorks = MutableStateFlow(!prefs.getBoolean(KEY_SEEN_INTRO, false))
    val showHowItWorks: StateFlow<Boolean> = _showHowItWorks.asStateFlow()

    fun openHowItWorks() {
        _showHowItWorks.value = true
    }

    fun dismissHowItWorks() {
        _showHowItWorks.value = false
        prefs.edit().putBoolean(KEY_SEEN_INTRO, true).apply()
    }

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
        TrackingManager.startWalk(
            relaxedThresholds = _testMode.value,
            activityType = _activityType.value,
        )
        // In test mode we feed points from map taps, so skip the GPS service.
        if (!_testMode.value) LocationService.start(getApplication())
    }

    fun stopWalk() {
        if (!_testMode.value) LocationService.stop(getApplication())
        // Claims the loop if it's ready to close; otherwise abandons the walk.
        TrackingManager.finishWalk()
    }

    /**
     * Abandon the walk in progress without claiming. Distinct from [stopWalk],
     * which claims when the loop is closable — the UI asks for confirmation
     * before calling this so an unfinished route is never silently thrown away.
     */
    fun cancelWalk() {
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

    /** Re-insert a previously deleted territory (backs the undo-delete snackbar). */
    fun restoreTerritory(territory: Territory) {
        viewModelScope.launch {
            repository.claim(territory)
            SyncScheduler.requestSync(getApplication())
        }
    }

    /** Save edited free-form notes for a territory and re-sync it. */
    fun updateNotes(id: String, notes: String) {
        val territory = territories.value.firstOrNull { it.id == id } ?: return
        if (territory.notes == notes) return
        viewModelScope.launch {
            repository.claim(territory.copy(notes = notes, syncStatus = SyncStatus.PENDING))
            SyncScheduler.requestSync(getApplication())
        }
    }

    /** Change a territory's fill/outline color and re-sync it. */
    fun recolorTerritory(id: String, colorHex: String) {
        val territory = territories.value.firstOrNull { it.id == id } ?: return
        if (territory.colorHex == colorHex) return
        viewModelScope.launch {
            repository.claim(territory.copy(colorHex = colorHex, syncStatus = SyncStatus.PENDING))
            SyncScheduler.requestSync(getApplication())
        }
    }

    private companion object {
        const val PREFS_NAME = "enclose_ui"
        const val KEY_SEEN_INTRO = "seen_intro"
        const val KEY_ACTIVITY = "activity_type"
        const val KEY_BASEMAP = "basemap_style"
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
