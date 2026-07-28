package io.app.enclose.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.EncloseApp
import io.app.enclose.export.GpxImporter
import io.app.enclose.data.Conquest
import io.app.enclose.data.MapCamera
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
import io.app.enclose.geo.LatLng
import io.app.enclose.offline.OfflineTilesScheduler
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.InputStream

class EncloseViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as EncloseApp).repository
    private val walkRepository = (app as EncloseApp).walkRepository
    private val cityTagger = (app as EncloseApp).cityTagger
    private val offlineTileSync = (app as EncloseApp).offlineTileSync

    /** Everything remembered between launches. See [UserSettings]. */
    private val settings = (app as EncloseApp).settings

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

    /**
     * The recorded walks, keyed by id. A claim shares its id with the walk that
     * produced it, so the detail screen can show how the ground was actually
     * covered — duration, pace, climb — none of which the territory itself knows.
     */
    val walksById: StateFlow<Map<String, Walk>> =
        walkRepository.walks
            .map { walks -> walks.associateBy { it.id } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap(),
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
        runCatching { ActivityType.valueOf(settings.activityTypeName ?: "") }
            .getOrDefault(ActivityType.WALK),
    )
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    fun setActivityType(type: ActivityType) {
        _activityType.value = type
        settings.activityTypeName = type.name
    }

    /**
     * Which basemap the map draws. Follows the system theme until the user picks
     * a side with the map's own toggle, then stays put — legibility outdoors is a
     * separate concern from whether they want a dark app.
     */
    private val _basemapStyle = MutableStateFlow(
        runCatching { BasemapStyle.valueOf(settings.basemapStyleName ?: "") }
            .getOrDefault(BasemapStyle.SYSTEM),
    )
    val basemapStyle: StateFlow<BasemapStyle> = _basemapStyle.asStateFlow()

    fun setBasemapStyle(style: BasemapStyle) {
        _basemapStyle.value = style
        settings.basemapStyleName = style.name
    }

    /**
     * Where the map should open, read fresh at each call rather than cached.
     *
     * A rotation destroys and rebuilds the map, so it re-reads this — a value
     * snapshotted at construction would send the user back to wherever they
     * were when the app launched instead of where they just panned to. It is
     * deliberately not a flow: the map owns the live camera and reports it back
     * through [saveCamera], and feeding it back in would fight the gesture that
     * just moved it.
     */
    fun lastCamera(): MapCamera? = settings.camera

    /** Remember the framing the user panned/zoomed to. */
    fun saveCamera(camera: MapCamera) {
        settings.camera = camera
        // Where the map is pointed is the honest measure of which cached city
        // earns its disk space, so the camera settling is what counts a visit.
        viewModelScope.launch {
            offlineTileSync.recordVisit(LatLng(camera.lat, camera.lng))
        }
    }

    /**
     * Ask for the cached map to catch up with the claims. Needs the style and
     * screen density, which only the map knows, so they're passed in from there.
     */
    fun requestOfflineTiles(styleUrl: String, pixelRatio: Float) {
        settings.offlineStyleUrl = styleUrl
        settings.offlinePixelRatio = pixelRatio
        OfflineTilesScheduler.request(getApplication(), styleUrl, pixelRatio)
    }

    /**
     * The same request from somewhere with no map on screen — after a claim, for
     * instance. Uses whatever the map last reported; before it ever has, there
     * are no claims to cache either, so skipping is correct.
     */
    private fun requestOfflineTiles() {
        val styleUrl = settings.offlineStyleUrl ?: return
        OfflineTilesScheduler.request(
            getApplication(),
            styleUrl,
            settings.offlinePixelRatio,
        )
    }

    /**
     * The place the map's home button returns to, or null until one is set.
     *
     * A flow rather than a per-call read like [lastCamera]: the button's icon,
     * its label and whether holding it does anything all follow this, so the UI
     * has to see it change the moment it is set or reset. Nothing sets it
     * automatically — a home the app guessed would be a home the user has to
     * notice and undo.
     */
    private val _home = MutableStateFlow(settings.home)
    val home: StateFlow<LatLng?> = _home.asStateFlow()

    fun setHome(point: LatLng) {
        _home.value = point
        settings.home = point
    }

    fun clearHome() {
        _home.value = null
        settings.home = null
    }

    /** How the territory list is ordered. Remembered between launches. */
    private val _territorySort = MutableStateFlow(
        runCatching { TerritorySort.valueOf(settings.territorySortName ?: "") }
            .getOrDefault(TerritorySort.RECENT),
    )
    val territorySort: StateFlow<TerritorySort> = _territorySort.asStateFlow()

    fun setTerritorySort(sort: TerritorySort) {
        _territorySort.value = sort
        settings.territorySortName = sort.name
    }

    /** Test mode: tap the map to inject points instead of walking with GPS. */
    private val _testMode = MutableStateFlow(settings.testMode)
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
    private val _showHowItWorks = MutableStateFlow(!settings.seenIntro)
    val showHowItWorks: StateFlow<Boolean> = _showHowItWorks.asStateFlow()

    fun openHowItWorks() {
        _showHowItWorks.value = true
    }

    fun dismissHowItWorks() {
        _showHowItWorks.value = false
        settings.seenIntro = true
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
            // JTS boolean geometry runs once per existing claim and gets slower
            // as the map fills up — far too much to put on the frame clock.
            val carved = withContext(Dispatchers.Default) {
                Conquest.carve(existing, territory, territory.claimedAtEpochMs)
            }
            // One transaction: carving is justified by the new claim, so the two
            // must never be able to land apart.
            repository.applyClaim(territory, carved)
            SyncScheduler.requestSync(getApplication())
            _claimEvents.tryEmit(territory)
            // Name the city afterwards: it needs a network, and the claim — the
            // thing the user actually walked for — must never wait on one.
            cityTagger.tag(territory.id, newRing)
            // Now that the claim has a city, its map may be worth keeping. The
            // worker waits for Wi-Fi, so nothing downloads on mobile data.
            requestOfflineTiles()
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
        settings.testMode = enabled
        // Leaving test mode abandons any tap-built walk in progress.
        if (!enabled && walk.value.isTracking) TrackingManager.cancelWalk()
    }

    /** Inject a tapped point. The first tap auto-starts a (serviceless) walk. */
    fun addTestPoint(point: LatLng) {
        if (!_testMode.value) return
        if (!walk.value.isTracking) startWalk()
        TrackingManager.onLocation(point)
    }

    /**
     * How the GPX import is going, for the UI to show and then clear. Null when
     * there is nothing to say.
     */
    private val _gpxImport = MutableStateFlow<GpxImport?>(null)
    val gpxImport: StateFlow<GpxImport?> = _gpxImport.asStateFlow()

    fun dismissGpxImport() {
        _gpxImport.value = null
    }

    /**
     * Replay a GPX track as a test walk: the same injection path as tapping the
     * map, fed from a route recorded elsewhere. Test mode only — outside it the
     * points would be competing with real GPS for the same walk.
     *
     * Any walk in progress is abandoned first. In test mode that can only be
     * another tapped or imported route, never one someone went out and walked,
     * so there is nothing here that the no-data-loss rule protects.
     *
     * The loop is deliberately *not* closed at the end. Importing a track is the
     * same as walking it — whether it becomes a claim is still the user's call,
     * made with Stop, exactly as [TrackingManager] requires everywhere else.
     *
     * Every stage reports itself. An import is one of the few things in this app
     * where nothing on screen necessarily changes — a track from another city
     * lands entirely off camera — so silence is indistinguishable from a feature
     * that doesn't work. [GpxImport.Done] carries the route back so the map can
     * go and show it.
     */
    fun importGpx(uri: Uri) {
        if (!_testMode.value) {
            _gpxImport.value = GpxImport.Failed(
                "Turn on test mode first — imported points stand in for GPS fixes.",
            )
            return
        }
        viewModelScope.launch {
            _gpxImport.value = GpxImport.Reading
            val points = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { stream -> GpxImporter.parse(stream.readCapped(MAX_GPX_BYTES)) }
                }.getOrNull()
            }

            if (points == null) {
                _gpxImport.value = GpxImport.Failed("Couldn't read that file.")
                return@launch
            }
            if (points.size < 2) {
                _gpxImport.value = GpxImport.Failed(
                    "No track points in that file — looked for <trkpt>, <rtept> and <wpt>.",
                )
                return@launch
            }

            if (walk.value.isTracking) TrackingManager.cancelWalk()
            startWalk()
            _gpxImport.value = GpxImport.Replaying(done = 0, total = points.size)
            points.forEachIndexed { index, point ->
                // No timestamp: the motion gate is bypassed, as with map taps.
                // An imported track jumps between fixes by design and would
                // otherwise read as a vehicle on its very first segment.
                TrackingManager.onLocation(
                    point = point.position,
                    altitudeMeters = point.elevationMeters,
                )
                // Replaying runs on the main dispatcher because the tracker's
                // state is read straight afterwards; handing the frame back
                // every so often is what lets the progress actually move
                // instead of the screen sitting frozen until it's over.
                if ((index + 1) % REPLAY_CHUNK == 0) {
                    _gpxImport.value = GpxImport.Replaying(index + 1, points.size)
                    yield()
                }
            }

            val walked = walk.value
            _gpxImport.value = GpxImport.Done(
                headline = "${points.size} points · ${formatDistance(walked.distanceMeters)}" +
                    " · ${formatClimb(walked.elevationGainMeters)} climb",
                // The recorded path is shorter than the file whenever points sit
                // closer together than the jitter filter allows, which is most
                // real tracks. Say so, or the counts look like a bug.
                detail = buildString {
                    if (walked.path.size < points.size) {
                        append(
                            "${walked.path.size} kept — the rest sat closer together " +
                                "than the jitter filter allows. ",
                        )
                    }
                    append(
                        if (walked.readyToClose) {
                            "The loop closes here: press Close loop & claim to keep it."
                        } else {
                            "The track doesn't end near where it starts, so it can't be " +
                                "claimed as a loop."
                        },
                    )
                },
                route = walked.path,
            )
        }
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

    /**
     * Read at most [maxBytes], so a file picked by mistake can't be pulled into
     * memory whole.
     *
     * `readNBytes` rather than a hand-rolled loop over a `Reader`: the loop this
     * replaces treated a zero-length read as progress and went round again, so a
     * provider that returned 0 without hitting EOF — which the documents
     * provider does — span forever on the IO dispatcher with the import dialog
     * up and no way out but killing the app. A capped read that cannot make
     * negative progress is the whole point.
     */
    private fun InputStream.readCapped(maxBytes: Int): String =
        readNBytes(maxBytes).toString(Charsets.UTF_8)

    private fun TrackingManager.PendingClaim.toWalk(claimed: Boolean) = Walk(
        id = id,
        ring = ring,
        areaSqMeters = areaSqMeters,
        perimeterMeters = perimeterMeters,
        distanceToStartMeters = distanceToStartMeters,
        closedAtEpochMs = closedAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        elevationGainMeters = elevationGainMeters,
        movingMs = movingMs,
        claimed = claimed,
        syncStatus = SyncStatus.PENDING,
    )

    private companion object {
        /**
         * ~8 MB of GPX — a couple of hundred thousand track points, well past
         * any single walk. Anything larger is the wrong file.
         */
        const val MAX_GPX_BYTES = 8_000_000

        /**
         * Points replayed between yields. Small enough that the progress bar
         * moves smoothly on a long track, large enough that the yielding itself
         * doesn't dominate the replay of a short one.
         */
        const val REPLAY_CHUNK = 100
    }
}

/**
 * What a GPX import is doing, so the UI can show it happening rather than
 * leaving the user to guess from a map that may not visibly change at all.
 */
sealed interface GpxImport {

    /** Opening and parsing the file. Length unknown until it's read. */
    data object Reading : GpxImport

    /** Feeding the parsed points through the tracker, [done] of [total]. */
    data class Replaying(val done: Int, val total: Int) : GpxImport

    /**
     * The track is in. [route] is the path as actually recorded, for the map to
     * frame — without it an import of somewhere else looks like nothing happened.
     */
    data class Done(
        val headline: String,
        val detail: String,
        val route: List<LatLng>,
    ) : GpxImport

    /** Nothing was imported, and this is why. */
    data class Failed(val reason: String) : GpxImport
}
