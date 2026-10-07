package io.app.enclose.ui

import android.app.Application
import android.content.res.Resources
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.app.enclose.R
import io.app.enclose.BuildConfig
import io.app.enclose.EncloseApp
import io.app.enclose.export.Backup
import io.app.enclose.export.GpxImporter
import io.app.enclose.export.ImportClosure
import io.app.enclose.data.BackupReport
import io.app.enclose.data.Conquest
import io.app.enclose.data.MapCamera
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.offline.OfflineTilesScheduler
import io.app.enclose.sync.SyncScheduler
import io.app.enclose.tracking.ActivityType
import io.app.enclose.tracking.RecordingFailure
import io.app.enclose.tracking.VoidReason
import io.app.enclose.tracking.LocationService
import io.app.enclose.tracking.TrackingManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.InputStream

class EncloseViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Read per use rather than captured, so a language change is picked up by
     * the next message instead of the next process.
     */
    private val res: Resources get() = getApplication<Application>().resources

    private val repository = (app as EncloseApp).repository
    private val walkRepository = (app as EncloseApp).walkRepository
    private val cityTagger = (app as EncloseApp).cityTagger
    private val snapTagger = (app as EncloseApp).snapTagger
    private val offlineTileSync = (app as EncloseApp).offlineTileSync
    private val backupRepository = (app as EncloseApp).backupRepository

    /** Everything remembered between launches. See [UserSettings]. */
    private val settings = (app as EncloseApp).settings

    init {
        // Saving each closed loop and stopping the GPS on a void are done in
        // EncloseApp, not here: both have to happen with no screen open (a walk
        // stopped from the watch, a void while the phone is pocketed). What stays
        // here is what only matters to a screen.
        //
        // A walk voided for vehicle movement: hand the reason to the UI to explain.
        viewModelScope.launch {
            TrackingManager.voidEvents.collect { reason ->
                _voidedWalk.value = reason
            }
        }
        // The recorder couldn't start (or couldn't carry on). The service has
        // already stopped itself, so there is nothing to shut down here — only
        // something to say, which is the whole point: this used to be silent.
        viewModelScope.launch {
            TrackingManager.recordingFailures.collect { failure ->
                _recordingFailure.value = failure
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
        ActivityType.resolve(settings.activityTypeName),
    )
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    fun setActivityType(type: ActivityType) {
        // Guarded rather than trusted: the UI greys out the modes that are off,
        // but the declared type sets the speed ceiling, so it must not be
        // possible to end up on one by any other route.
        if (!type.available) return
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

    /**
     * Whether the bottom panel is minimised to a single row. Remembered, so the
     * map the user set up stays set up — see [UserSettings.panelCollapsed].
     */
    private val _panelCollapsed = MutableStateFlow(settings.panelCollapsed)
    val panelCollapsed: StateFlow<Boolean> = _panelCollapsed.asStateFlow()

    fun setPanelCollapsed(collapsed: Boolean) {
        _panelCollapsed.value = collapsed
        settings.panelCollapsed = collapsed
    }

    /**
     * Whether the walk may float over other apps in a picture-in-picture window.
     * Nothing turns this on but the user — see [UserSettings.floatingWindow].
     */
    private val _floatingWindow = MutableStateFlow(settings.floatingWindow)
    val floatingWindow: StateFlow<Boolean> = _floatingWindow.asStateFlow()

    fun setFloatingWindow(enabled: Boolean) {
        _floatingWindow.value = enabled
        settings.floatingWindow = enabled
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

    /**
     * Whether the developer affordances exist in this build at all.
     *
     * Test mode replaces GPS with map taps, so a user who finds the switch in a
     * shipped build gets a walk that records nothing and a route they can't get
     * back — the same failure the whole `LocationReadiness` path exists to stop,
     * only self-inflicted. Read once here rather than at each call site so there
     * is one answer to "is this a dev build".
     */
    val devToolsAvailable: Boolean get() = BuildConfig.DEBUG

    /**
     * Test mode: tap the map to inject points instead of walking with GPS.
     *
     * Forced off where [devToolsAvailable] is false rather than merely hidden: a
     * stored `true` (from a debug build, or a restored backup) would otherwise
     * survive into a release build as a walk that silently never starts the
     * location service.
     */
    private val _testMode = MutableStateFlow(devToolsAvailable && settings.testMode)
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

    /**
     * Set when the recorder couldn't run at all. The UI explains it and calls
     * [dismissRecordingFailure].
     *
     * Distinct from [voidedWalk]: nothing was walked and nothing was thrown away.
     * This is the app admitting it can't do the thing it just said it was doing,
     * which it previously did by stopping the location service in silence and
     * leaving a walk on screen that could never record a metre.
     */
    private val _recordingFailure = MutableStateFlow<RecordingFailure?>(null)
    val recordingFailure: StateFlow<RecordingFailure?> = _recordingFailure.asStateFlow()

    fun dismissRecordingFailure() {
        _recordingFailure.value = null
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
        // Match the route onto real roads, if the user has asked for that.
        //
        // Deliberately its own coroutine, and deliberately on applicationScope.
        // Chaining it behind cityTagger would put a slow remote match in a queue
        // behind the geocoder's ten-second timeout, and viewModelScope dies with
        // the screen — which someone can easily leave in the seconds after
        // claiming. Nothing here is on the path to the claim being saved; that
        // already happened above.
        (getApplication() as EncloseApp).applicationScope.launch {
            snapTagger.tag(territory.id, newRing)
        }
    }

    /** User dismissed the modal without claiming. */
    fun discardClaim() = TrackingManager.clearPending()

    /**
     * Whether the walk in progress is fed by injected points — map taps in test
     * mode, or a replayed GPX track — rather than by [LocationService].
     *
     * Tracked rather than re-derived from [testMode], because a GPX import runs
     * outside test mode too and the two need opposite things: an injected walk
     * never started the service, and a real one must always stop it. Reading the
     * switch at stop time also gets it wrong on its own — a walk started with the
     * switch in one position can be stopped with it in the other.
     *
     * Observable because the panel has to know as well: an imported walk with a
     * GPS accuracy chip beside it reads as "acquiring…" forever, describing a
     * receiver that was never switched on.
     */
    private val _injectedWalk = MutableStateFlow(false)
    val injectedWalk: StateFlow<Boolean> = _injectedWalk.asStateFlow()

    fun startWalk() = beginWalk(injected = _testMode.value)

    /**
     * Start a walk, with [injected] saying where the points will come from.
     * Injected walks use relaxed distance thresholds (a tapped or replayed route
     * jumps between points) and never run the location service.
     */
    private fun beginWalk(injected: Boolean) {
        // The previous attempt's explanation doesn't belong on top of this one.
        _recordingFailure.value = null
        _injectedWalk.value = injected
        TrackingManager.startWalk(
            relaxedThresholds = injected,
            activityType = _activityType.value,
        )
        if (!injected) LocationService.start(getApplication())
    }

    fun stopWalk() {
        if (!_injectedWalk.value) LocationService.stop(getApplication())
        // Claims the loop if it's ready to close; otherwise abandons the walk.
        TrackingManager.finishWalk()
    }

    /**
     * Abandon the walk in progress without claiming. Distinct from [stopWalk],
     * which claims when the loop is closable — the UI asks for confirmation
     * before calling this so an unfinished route is never silently thrown away.
     */
    fun cancelWalk() {
        if (!_injectedWalk.value) LocationService.stop(getApplication())
        TrackingManager.cancelWalk()
    }

    fun setTestMode(enabled: Boolean) {
        // Guarded rather than trusted, in the same idiom as setActivityType: the
        // switch is hidden in a release build, so it must not be reachable by any
        // other route either.
        if (enabled && !devToolsAvailable) return
        _testMode.value = enabled
        settings.testMode = enabled
        // Leaving test mode abandons any tap-built walk in progress. Only a
        // tapped one: an imported walk can be running outside test mode, and a
        // real GPS walk must never be thrown away by a settings toggle.
        if (!enabled && walk.value.isTracking && _injectedWalk.value) TrackingManager.cancelWalk()
    }

    /**
     * Whether claimed routes may be matched onto real roads and paths.
     *
     * Off by default: this is the only thing in the app that sends a precise
     * record of where somebody walked anywhere. See [UserSettings.snapToPaths].
     */
    private val _snapToPaths = MutableStateFlow(settings.snapToPaths)
    val snapToPaths: StateFlow<Boolean> = _snapToPaths.asStateFlow()

    /** False where no matching service is bound, so the switch can be hidden. */
    val snapAvailable: Boolean get() = snapTagger.isAvailable

    fun setSnapToPaths(enabled: Boolean) {
        _snapToPaths.value = enabled
        settings.snapToPaths = enabled
        // Deliberately no backfill here. Turning a switch on must not, by itself,
        // upload a walking history — that takes the explicit action below, which
        // says how many walks it would send first.
        if (enabled) refreshSnapBacklog()
    }

    /**
     * How many claims [snapExistingClaims] would upload, or null while unknown.
     * Shown on the button so nobody presses it blind.
     */
    private val _snapBacklog = MutableStateFlow<Int?>(null)
    val snapBacklog: StateFlow<Int?> = _snapBacklog.asStateFlow()

    fun refreshSnapBacklog() {
        viewModelScope.launch { _snapBacklog.value = snapTagger.pendingCount() }
    }

    /** True while a backfill is running, so the button can say so. */
    private val _snappingExisting = MutableStateFlow(false)
    val snappingExisting: StateFlow<Boolean> = _snappingExisting.asStateFlow()

    /**
     * Match every claim that has never been offered to the matcher.
     *
     * Only ever from a button the user pressed, and on [applicationScope] because
     * it is a long run of network calls that shouldn't die because the profile
     * screen was closed half way through.
     */
    fun snapExistingClaims() {
        if (_snappingExisting.value) return
        _snappingExisting.value = true
        (getApplication() as EncloseApp).applicationScope.launch {
            try {
                snapTagger.backfill()
            } finally {
                _snappingExisting.value = false
                _snapBacklog.value = snapTagger.pendingCount()
            }
        }
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
     * Replay a GPX track as a walk: the same injection path as tapping the map,
     * fed from a route recorded elsewhere — the picker in the profile screen, or
     * a track shared straight into Enclose from another app (see
     * `MainActivity`'s SEND/VIEW filters).
     *
     * **Not test mode only, and that is a deliberate widening.** Recording with
     * a watch or a phone health app and claiming the loop afterwards is a real
     * way to use this, and it has to work in the build that ships — where test
     * mode no longer exists. It has a cost worth stating plainly: replayed points
     * carry no timestamps, so the motion gate is bypassed exactly as it is for map
     * taps, and a GPX of a drive will claim territory. Everything else about a
     * claim is unchanged — the loop still has to close, and Stop is still what
     * closes it. If a backend or a leaderboard ever lands, this is the hole to
     * close first.
     *
     * A walk fed by GPS is never thrown away for an import: the loop someone is
     * out walking outranks the file they just tapped, so the import is refused
     * rather than replacing it. Another injected walk (tapped or imported) is
     * abandoned, since nothing there was walked.
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
        // A share can arrive at any moment, including on top of an import that is
        // still replaying. Two replays feeding one tracker would interleave two
        // routes into a single walk.
        if (_gpxImport.value?.isRunning == true) return
        if (walk.value.isTracking && !_injectedWalk.value) {
            _gpxImport.value = GpxImport.Failed(res.getString(R.string.import_refused_walking))
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
                _gpxImport.value = GpxImport.Failed(res.getString(R.string.import_unreadable))
                return@launch
            }
            if (points.size < 2) {
                _gpxImport.value = GpxImport.Failed(res.getString(R.string.import_no_points))
                return@launch
            }

            if (walk.value.isTracking) TrackingManager.cancelWalk()
            // Injected regardless of test mode: these points stand in for fixes,
            // so the location service must stay out of the way and the jitter
            // thresholds have to be the relaxed ones.
            beginWalk(injected = true)
            // End on the walker's last return to the start, not wherever the
            // recorder was finally switched off — see ImportClosure. Asked after
            // beginWalk, which is what puts the relaxed thresholds in force.
            val endIndex = ImportClosure.endIndex(
                points.map { it.position },
                closeRadiusMeters = TrackingManager.closureRadiusMeters,
                leaveRadiusMeters = TrackingManager.leaveStartRadiusMeters,
                minPerimeterMeters = TrackingManager.minPerimeterMeters,
            )
            val replayed = if (endIndex != null) points.subList(0, endIndex + 1) else points
            val overshootMeters = if (endIndex != null) {
                Geo.pathLengthMeters(points.subList(endIndex, points.size).map { it.position })
            } else {
                0.0
            }
            _gpxImport.value = GpxImport.Replaying(done = 0, total = replayed.size)
            replayed.forEachIndexed { index, point ->
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
                    _gpxImport.value = GpxImport.Replaying(index + 1, replayed.size)
                    yield()
                }
            }

            val walked = walk.value
            _gpxImport.value = GpxImport.Done(
                headline = res.getQuantityString(
                    R.plurals.import_headline,
                    points.size,
                    points.size,
                    res.formatDistance(walked.distanceMeters),
                    res.formatClimb(walked.elevationGainMeters),
                ),
                // The recorded path is shorter than the file whenever points sit
                // closer together than the jitter filter allows, which is most
                // real tracks. Say so, or the counts look like a bug.
                detail = listOfNotNull(
                    if (walked.path.size < replayed.size) {
                        res.getQuantityString(
                            R.plurals.import_kept,
                            walked.path.size,
                            walked.path.size,
                        )
                    } else {
                        null
                    },
                    // Said, because the claim is now shorter than the file: the
                    // part after the return to the start is left out of it.
                    if (overshootMeters >= MIN_REPORTED_OVERSHOOT_M) {
                        res.getString(R.string.import_overshoot, res.formatDistance(overshootMeters))
                    } else {
                        null
                    },
                    if (walked.readyToClose) {
                        res.getString(R.string.import_closes)
                    } else {
                            // How far off, and how close it needed to be: "doesn't
                            // end near" alone leaves the user guessing whether a
                            // few metres or a few kilometres were missing.
                        res.getString(
                            R.string.import_never_closes,
                            res.formatDistance(ImportClosure.endGapMeters(points.map { it.position })),
                            res.formatDistance(TrackingManager.closureRadiusMeters),
                        )
                    },
                ).joinToString(" "),
                route = walked.path,
            )
        }
    }

    /**
     * How a backup or a restore is going. Modelled like [gpxImport], and for the
     * same reason: it happens off screen, and silence is indistinguishable from
     * a feature that doesn't work.
     */
    private val _backup = MutableStateFlow<BackupJob?>(null)
    val backup: StateFlow<BackupJob?> = _backup.asStateFlow()

    fun dismissBackup() {
        _backup.value = null
    }

    /** The name to offer the file picker, dated so a folder of backups reads. */
    fun suggestedBackupFileName(): String = Backup.fileName(System.currentTimeMillis())

    /**
     * Write every claim, walk, the profile, the walk in progress, the cached
     * regions and every setting to [uri] as one file.
     *
     * Exporting is allowed **during a walk**, deliberately: the walk in progress
     * is already mirrored to disk fix by fix ([WalkProgressRepository]), so what
     * lands in the file is a true snapshot, and refusing would deny a backup to
     * someone half way round a loop who is about to change phones.
     */
    fun exportBackup(uri: Uri) {
        if (_backup.value?.isRunning == true) return
        viewModelScope.launch {
            _backup.value = BackupJob.Exporting
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = backupRepository.collect(
                        appVersionName = BuildConfig.VERSION_NAME,
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                    val text = Backup.encode(data)
                    // Truncating first, because a picker that "creates" a file
                    // will happily hand back one the user chose to overwrite —
                    // and a shorter backup written over a longer one would
                    // otherwise leave the tail of the old file attached to it.
                    app.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("no output stream")
                    data to text.length
                }
            }
            _backup.value = result.fold(
                onSuccess = { (data, bytes) ->
                    BackupJob.Done(
                        headline = res.getString(
                            R.string.backup_export_headline,
                            res.getQuantityString(
                                R.plurals.backup_claims,
                                data.territories.size,
                                data.territories.size,
                            ),
                            res.getQuantityString(R.plurals.backup_walks, data.walks.size, data.walks.size),
                            res.formatFileSize(bytes.toLong()),
                        ),
                        detail = res.getString(R.string.backup_export_detail),
                    )
                },
                onFailure = {
                    BackupJob.Failed(res.getString(R.string.backup_export_failed))
                },
            )
        }
    }

    /**
     * Read a backup file at [uri] and merge it into this device.
     *
     * **Refused while a walk is running.** A restore rewrites `walk_progress`,
     * which is where the walk being recorded right now lives; the points already
     * walked exist nowhere else, and no file is worth them. Everything else is a
     * merge — see [io.app.enclose.data.BackupRepository] — so nothing already on
     * the device is deleted by this.
     */
    fun importBackup(uri: Uri) {
        if (_backup.value?.isRunning == true) return
        if (walk.value.isTracking) {
            _backup.value = BackupJob.Failed(res.getString(R.string.backup_refused_walking))
            return
        }
        viewModelScope.launch {
            _backup.value = BackupJob.Importing
            val app = getApplication<Application>()
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        // One byte past the cap, so a file that fills it exactly
                        // can be told apart from one that was truncated — the
                        // difference between "too large" and "not valid JSON",
                        // which are very different things to be told.
                        stream.readNBytes(MAX_BACKUP_BYTES + 1)
                    }
                }.getOrNull()
            }
            if (text == null) {
                _backup.value = BackupJob.Failed(res.getString(R.string.backup_unreadable))
                return@launch
            }
            if (text.size > MAX_BACKUP_BYTES) {
                _backup.value = BackupJob.Failed(
                    res.getString(
                        R.string.backup_too_large,
                        res.formatFileSize(MAX_BACKUP_BYTES.toLong()),
                    ),
                )
                return@launch
            }

            val decoded = withContext(Dispatchers.Default) {
                Backup.decode(
                    text = text.toString(Charsets.UTF_8),
                    currentSchemaVersion = backupRepository.currentSchemaVersion(),
                )
            }
            when (decoded) {
                is Backup.Decoded.Failed -> _backup.value = BackupJob.Failed(describe(decoded.problem))
                is Backup.Decoded.Ok -> {
                    val report = runCatching { backupRepository.restore(decoded.data) }.getOrNull()
                    _backup.value = if (report == null) {
                        BackupJob.Failed(res.getString(R.string.backup_restore_failed))
                    } else {
                        // Re-read the settings that are held in memory: they were
                        // loaded at construction, so without this the restored
                        // home, basemap and the rest sit on disk while the screen
                        // goes on showing what was there before.
                        reloadSettings()
                        BackupJob.Done(
                            headline = restoreHeadline(report),
                            detail = listOfNotNull(
                                decoded.newerSchema?.let {
                                    res.getString(
                                        R.string.restore_newer_schema,
                                        it.fileSchema,
                                        it.currentSchema,
                                    )
                                },
                                restoreDetail(report),
                            ).joinToString("\n\n"),
                        )
                    }
                }
            }
        }
    }

    /**
     * Pull the settings back out of storage into the flows the UI reads.
     *
     * These are cached in memory at construction — every `MutableStateFlow` above
     * is seeded from [settings] — so a restore that only wrote the preferences
     * file would be invisible until the app was killed and reopened, which reads
     * exactly like a restore that didn't work.
     */
    private fun reloadSettings() {
        _activityType.value = ActivityType.resolve(settings.activityTypeName)
        _basemapStyle.value = runCatching {
            BasemapStyle.valueOf(settings.basemapStyleName ?: "")
        }.getOrDefault(BasemapStyle.SYSTEM)
        _home.value = settings.home
        _panelCollapsed.value = settings.panelCollapsed
        _floatingWindow.value = settings.floatingWindow
        _snapToPaths.value = settings.snapToPaths
        _testMode.value = devToolsAvailable && settings.testMode
        _territorySort.value = runCatching {
            TerritorySort.valueOf(settings.territorySortName ?: "")
        }.getOrDefault(TerritorySort.RECENT)
        // `seenIntro` is deliberately not pushed into [showHowItWorks]. It is
        // restored on disk and read at the next launch like any other; acting on
        // it here would throw the explainer sheet up over the report of the
        // restore that had just finished.
    }

    /** Why [Backup.decode] refused a file, in the user's language. */
    private fun describe(problem: Backup.Problem): String = when (problem) {
        Backup.Problem.NotJson -> res.getString(R.string.backup_not_json)
        Backup.Problem.NotABackup -> res.getString(R.string.backup_not_backup)
        Backup.Problem.NoFormatVersion -> res.getString(R.string.backup_no_format)
        is Backup.Problem.NewerFormat -> res.getString(
            R.string.backup_newer_format,
            problem.fileFormat,
            problem.supportedFormat,
        )
    }

    private fun restoreHeadline(report: BackupReport): String {
        val claims = report.territoriesAdded + report.territoriesReplaced
        val walks = report.walksAdded + report.walksReplaced
        return res.getString(
            R.string.restore_headline,
            res.getQuantityString(
                R.plurals.restore_new_claims,
                report.territoriesAdded,
                report.territoriesAdded,
                claims,
            ),
            res.getQuantityString(R.plurals.restore_new_walks, report.walksAdded, report.walksAdded, walks),
        )
    }

    /**
     * The parts of a restore the counts don't cover — each line is there because
     * its absence would have to be discovered by the user instead.
     */
    private fun restoreDetail(report: BackupReport): String {
        val replaced = report.territoriesReplaced + report.walksReplaced
        return listOfNotNull(
            res.getString(R.string.restore_nothing_deleted),
            if (replaced > 0) {
                res.getQuantityString(R.plurals.restore_replaced, replaced, replaced)
            } else {
                null
            },
            if (report.profileRestored) res.getString(R.string.restore_profile) else null,
            if (report.walkInProgressRestored) res.getString(R.string.restore_walk_resumed) else null,
            if (report.walkInProgressSkipped) res.getString(R.string.restore_walk_skipped) else null,
            if (report.offlineRegionsSkipped > 0) {
                res.getString(R.string.restore_offline_skipped)
            } else {
                null
            },
        ).joinToString(" ")
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

    private companion object {
        /** An overshoot shorter than this is GPS wander at the finish, not worth a sentence. */
        const val MIN_REPORTED_OVERSHOOT_M = 20.0

        /**
         * ~8 MB of GPX — a couple of hundred thousand track points, well past
         * any single walk. Anything larger is the wrong file.
         */
        const val MAX_GPX_BYTES = 8_000_000

        /**
         * ~64 MB of backup. A backup is the whole database as text, and the
         * database is dominated by walked points: at roughly 50 bytes a point
         * this is a few hundred thousand of them, past a lifetime of walking.
         * The cap exists so a video picked by mistake can't be pulled into
         * memory whole on a phone.
         */
        const val MAX_BACKUP_BYTES = 64_000_000

        /**
         * Points replayed between yields. Small enough that the progress bar
         * moves smoothly on a long track, large enough that the yielding itself
         * doesn't dominate the replay of a short one.
         */
        const val REPLAY_CHUNK = 100
    }
}

/**
 * What a backup or a restore is doing.
 *
 * One type for both directions: they are never running at the same time (each
 * refuses to start while the other is), and a single state is what stops the UI
 * having to decide which of two reports to show.
 */
sealed interface BackupJob {

    /** Reading the database and writing the file. */
    data object Exporting : BackupJob

    /** Reading the file and writing it into the database. */
    data object Importing : BackupJob

    /** Finished. Both directions report counts, because both can surprise. */
    data class Done(val headline: String, val detail: String) : BackupJob

    /** Nothing was written, and this is why. */
    data class Failed(val reason: String) : BackupJob

    /** True only while work is actually happening — see [GpxImport.isRunning]. */
    val isRunning: Boolean get() = this is Exporting || this is Importing
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

    /**
     * True while the file is still being turned into a walk. [Done] and [Failed]
     * are reports left on screen for the user to dismiss, not work in progress,
     * so a second import may start on top of them.
     */
    val isRunning: Boolean get() = this is Reading || this is Replaying
}

/**
 * The [Walk] row for a closed loop. Shared by the two writers of that row:
 * [io.app.enclose.EncloseApp] saves it unclaimed the moment the loop closes,
 * and [EncloseViewModel.confirmClaim] re-saves it claimed under the same id.
 */
internal fun TrackingManager.PendingClaim.toWalk(claimed: Boolean) = Walk(
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
