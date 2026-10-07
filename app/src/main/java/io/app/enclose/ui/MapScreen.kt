package io.app.enclose.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.data.SnapDisplay
import io.app.enclose.data.Territory
import io.app.enclose.data.TerritoryHit
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.BlockReason
import io.app.enclose.R
import io.app.enclose.tracking.ActivityType
import io.app.enclose.tracking.MotionGate
import io.app.enclose.tracking.NameGenerator
import io.app.enclose.tracking.RecordingFailure
import io.app.enclose.tracking.VoidReason
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.theme.LocalEncloseAccents
import io.app.enclose.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * The home screen: a full-bleed map with floating controls.
 *
 * Layout contract — the map is edge-to-edge and everything else floats above
 * it. The bottom control panel reports its measured height so the snackbar,
 * the map's right-hand control rail and MapLibre's own attribution can all sit
 * clear of it instead of underneath (the snackbar used to be hidden by it).
 */
@Composable
fun MapScreen(
    viewModel: EncloseViewModel,
    /** Whether location can produce a fix worth recording, and if not, why not. */
    location: LocationReadiness,
    onRequestPermission: () -> Unit,
    /** Opens this app's settings page — where the Precise location toggle lives. */
    onOpenAppSettings: () -> Unit = {},
    /** Opens the device's location settings, for the master switch. */
    onOpenLocationSettings: () -> Unit = {},
    /** True while Enclose shares the screen with another app. */
    inMultiWindow: Boolean = false,
    /** False where asking for split screen could never work — see [SplitScreenSupport]. */
    splitScreenSupported: Boolean = false,
    /** Asks the system for a split-screen half; false if it refused outright. */
    onRequestSplitScreen: () -> Boolean = { false },
    /** Whether the walk may float over other apps. */
    floatingWindowEnabled: Boolean = false,
    onSetFloatingWindow: (Boolean) -> Unit = {},
    /** False where the device has no picture-in-picture at all. */
    floatingWindowSupported: Boolean = false,
    /** Floats the walk now; false if the system refused. */
    onEnterFloatingWindow: () -> Boolean = { false },
    onOpenProfile: () -> Unit = {},
    onOpenTerritory: (String) -> Unit = {},
    /**
     * The claim a map tap picked out, by id. Hoisted because opening a claim
     * disposes this screen, and a selection kept here would not be there on the
     * way back — see [MainActivity].
     */
    selectedClaimId: String? = null,
    onSelectClaim: (String?) -> Unit = {},
    /** Points to fit the camera to once (e.g. from "Show on map"). */
    pendingFocus: List<LatLng>? = null,
    /** Called after [pendingFocus] has been consumed so it fires only once. */
    onFocusConsumed: () -> Unit = {},
    /** A territory deleted from the detail screen, to remove with an undo option. */
    pendingDelete: Territory? = null,
    /** Called after [pendingDelete] has been consumed so it fires only once. */
    onDeleteConsumed: () -> Unit = {},
    profileViewModel: ProfileViewModel = viewModel(),
) {
    val res = LocalResources.current
    val walk by viewModel.walk.collectAsStateWithLifecycle()
    val territories by viewModel.territories.collectAsStateWithLifecycle()
    val walksById by viewModel.walksById.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val injectedWalk by viewModel.injectedWalk.collectAsStateWithLifecycle()
    val activityType by viewModel.activityType.collectAsStateWithLifecycle()
    val pendingClaim by viewModel.pendingClaim.collectAsStateWithLifecycle()
    val showHowItWorks by viewModel.showHowItWorks.collectAsStateWithLifecycle()
    val voidedWalk by viewModel.voidedWalk.collectAsStateWithLifecycle()
    val recordingFailure by viewModel.recordingFailure.collectAsStateWithLifecycle()
    val gpxImport by viewModel.gpxImport.collectAsStateWithLifecycle()
    val backupJob by viewModel.backup.collectAsStateWithLifecycle()
    val basemapStyle by viewModel.basemapStyle.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    val panelCollapsed by viewModel.panelCollapsed.collectAsStateWithLifecycle()
    val territorySort by viewModel.territorySort.collectAsStateWithLifecycle()
    val profile by profileViewModel.state.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val controller = rememberMapController()
    // The basemap follows the system theme until the user overrides it.
    val basemapDark = basemapStyle.isDark(isSystemInDarkTheme())

    /**
     * The selected claim, or null — including when it has just been deleted,
     * which is how the card and the highlight go away without being told.
     */
    val selectedTerritory = territories.firstOrNull { it.id == selectedClaimId }

    var showList by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDiscardWalk by remember { mutableStateOf(false) }
    // Debug builds only, since that's the only place test mode exists: Start
    // while it's on begins a walk that records no GPS at all, and finding that
    // out at the end costs the whole route.
    var confirmTestWalk by remember { mutableStateOf(false) }
    // Home button dialogs: the position being offered as home, the reset
    // confirmation behind the hold, and "there's no fix to save yet".
    var confirmSetHome by remember { mutableStateOf<LatLng?>(null) }
    var confirmResetHome by remember { mutableStateOf(false) }
    var noFixForHome by remember { mutableStateOf(false) }
    // Multi-window: when a split-screen request lands nowhere, the user is told
    // how to do it from Recents rather than left with a button that did nothing.
    var splitRequestedAt by remember { mutableLongStateOf(0L) }
    /** Whether the app was already sharing the screen when the request went out. */
    var splitRequestedFrom by remember { mutableStateOf(false) }
    var showSplitHelp by remember { mutableStateOf(false) }
    var floatingRefused by remember { mutableStateOf(false) }
    // Measured height of the bottom panel, so floating UI can clear it.
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val panelHeight = with(density) { panelHeightPx.toDp() }

    // Measured height of the selected-claim card, and the space it takes at the
    // bottom of the map: everything anchored to the panel — both control rails,
    // the snackbar, and MapLibre's own logo and attribution — is raised by it.
    // A card that covers the last button on a rail makes that control
    // unreachable for as long as a claim is selected, and the attribution is a
    // licence requirement, not a decoration.
    var claimCardHeightPx by remember { mutableIntStateOf(0) }
    val claimCardSpace = if (selectedTerritory != null) {
        with(density) { claimCardHeightPx.toDp() } + CLAIM_CARD_GAP
    } else {
        0.dp
    }
    val claimCardSpacePx = with(density) { claimCardSpace.roundToPx() }

    // A split-screen half is about half the height the expanded panel was drawn
    // for, so the window itself gets a say in whether the panel is folded.
    val windowHeightDp = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
    }
    // Starting a walk folds the panel straight away: the moment there's a walk
    // to look at, the map is what you want the screen for. Kept apart from the
    // stored preference — this is about the walk, not a standing choice, so it
    // lifts when the walk ends rather than changing what an idle map looks like
    // tomorrow. Expanding it during a walk clears it, because an explicit choice
    // outranks a convenience.
    var autoCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(walk.isTracking) { autoCollapsed = walk.isTracking }

    val collapsePanel = WindowLayoutPolicy.collapsePanel(
        userCollapsed = panelCollapsed || autoCollapsed,
        heightDp = windowHeightDp,
    )

    // Celebrate each claimed loop, and frame what was just won.
    LaunchedEffect(Unit) {
        viewModel.claimEvents.collect { t ->
            controller.fitTo(t.ring)
            snackbarHost.showSnackbar(
                res.getString(R.string.claim_done_snackbar, t.name, res.formatArea(t.areaSqMeters)),
            )
        }
    }

    // Tell the downloader which basemap to cache. Runs whenever the style
    // changes, since a dark-mode switch means different tiles entirely.
    LaunchedEffect(basemapDark) {
        viewModel.requestOfflineTiles(
            styleUrl = basemapStyleUrl(basemapDark),
            pixelRatio = density.density,
        )
    }

    // Consume a one-shot focus request (e.g. "Show on map" from the detail screen).
    LaunchedEffect(pendingFocus, controller.isStyleLoaded) {
        val pts = pendingFocus
        if (!pts.isNullOrEmpty() && controller.isStyleLoaded) {
            controller.fitTo(pts)
            onFocusConsumed()
        }
    }

    // Delete a territory but keep it around so an UNDO snackbar can restore it.
    fun deleteWithUndo(territory: Territory) {
        viewModel.deleteTerritory(territory.id)
        scope.launch {
            val result = snackbarHost.showSnackbar(
                message = res.getString(R.string.map_deleted_snackbar, territory.name),
                actionLabel = res.getString(R.string.map_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreTerritory(territory)
            }
        }
    }

    // A delete initiated on the detail screen arrives here so the undo snackbar
    // shows on the map the user returns to.
    LaunchedEffect(pendingDelete) {
        pendingDelete?.let {
            deleteWithUndo(it)
            onDeleteConsumed()
        }
    }

    // --- Map controls: where each one goes depends on the room there is ---
    // Defined once, as data, so the ones that don't fit can be drawn in the
    // ⋮ menu instead without a second copy of what each one does.
    val controls = buildList {
        // Window controls come first: they're about where the app is, not
        // where the map is looking. They're also the first to move into the
        // menu when room runs short — you press them once, not mid-stride.
        if (floatingWindowSupported) {
            // Tap floats now and arms the automatic float for when the user
            // leaves the app mid-walk; holding disarms it again. One control,
            // the same tap-and-hold idiom as Home below.
            add(
                MapControlSpec(
                    control = MapControl.FLOAT,
                    icon = Icons.Filled.PictureInPictureAlt,
                    label = stringResource(R.string.map_float),
                    tint = if (floatingWindowEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onLongPress = if (floatingWindowEnabled) {
                        {
                            onSetFloatingWindow(false)
                            scope.launch {
                                snackbarHost.showSnackbar(res.getString(R.string.map_float_off_snackbar))
                            }
                        }
                    } else {
                        null
                    },
                    longPressLabel = stringResource(R.string.map_float_stop),
                    onClick = {
                        onSetFloatingWindow(true)
                        if (!onEnterFloatingWindow()) floatingRefused = true
                    },
                ),
            )
        }
        // Only where the request can actually land — see SplitScreenSupport.
        // A control that never works is worse than one that isn't there.
        if (splitScreenSupported) {
            add(
                MapControlSpec(
                    control = MapControl.SPLIT,
                    icon = Icons.Filled.Splitscreen,
                    // Live in both states: asked for from inside a split, the
                    // request re-pairs this task and takes the previous split
                    // down with it, which is the only handle an app has on one.
                    label = if (inMultiWindow) {
                        stringResource(R.string.map_split_rebuild)
                    } else {
                        stringResource(R.string.map_split_share)
                    },
                    tint = if (inMultiWindow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onClick = {
                        // Remembered so "did anything happen?" can be answered by
                        // comparing against the state we asked from, in either
                        // direction — into a split, or out of the old one.
                        splitRequestedFrom = inMultiWindow
                        if (onRequestSplitScreen()) {
                            splitRequestedAt = System.currentTimeMillis()
                        } else {
                            showSplitHelp = true
                        }
                    },
                ),
            )
        }
        add(
            MapControlSpec(
                control = MapControl.ZOOM_IN,
                icon = Icons.Filled.Add,
                label = stringResource(R.string.map_zoom_in),
                enabled = controller.isStyleLoaded,
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { controller.zoomBy(ZOOM_BUTTON_STEP) },
            ),
        )
        add(
            MapControlSpec(
                control = MapControl.ZOOM_OUT,
                icon = Icons.Filled.Remove,
                label = stringResource(R.string.map_zoom_out),
                enabled = controller.isStyleLoaded,
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { controller.zoomBy(-ZOOM_BUTTON_STEP) },
            ),
        )
        // Home: tap to fly back to it, hold to reset it. Unset, the tap
        // offers to save where the user is standing — nothing sets it
        // behind their back, since a guessed home is one they'd have to
        // notice and undo.
        add(
            MapControlSpec(
                control = MapControl.HOME,
                icon = if (home == null) Icons.Outlined.Home else Icons.Filled.Home,
                label = stringResource(if (home == null) R.string.map_home_set else R.string.map_home_go),
                // Flying home needs only a map; setting it needs a fix.
                enabled = if (home == null) controller.canLocate else controller.isStyleLoaded,
                tint = if (home == null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                onLongPress = if (home == null) null else ({ confirmResetHome = true }),
                longPressLabel = stringResource(R.string.map_home_reset),
                onClick = {
                    val saved = home
                    if (saved != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.flyTo(saved)
                    } else {
                        // Read once, and remember what was read: saving the fix
                        // the user was shown beats re-reading a newer one after
                        // they've walked on during the dialog.
                        val here = controller.currentLocation()
                        if (here != null) confirmSetHome = here else noFixForHome = true
                    }
                },
            ),
        )
        // Filled while the map is following, hollow once a pan has stopped
        // it: the same button both reports the state and is how you get
        // following back, so "why has it stopped keeping up?" answers itself.
        add(
            MapControlSpec(
                control = MapControl.RECENTER,
                icon = if (controller.followUser) {
                    Icons.Filled.MyLocation
                } else {
                    Icons.Filled.LocationSearching
                },
                label = stringResource(
                    if (controller.followUser) R.string.map_following else R.string.map_recenter,
                ),
                enabled = controller.canLocate,
                tint = if (controller.followUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controller.recenter()
                },
            ),
        )
        // Basemap toggle: the dark map is hard to read in bright sun. Shows
        // the map you'd get by tapping, not the one you're looking at.
        add(
            MapControlSpec(
                control = MapControl.BASEMAP,
                icon = if (basemapDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                label = stringResource(
                    if (basemapDark) R.string.map_basemap_light else R.string.map_basemap_dark,
                ),
                enabled = controller.isStyleLoaded,
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    viewModel.setBasemapStyle(
                        if (basemapDark) BasemapStyle.LIGHT else BasemapStyle.DARK,
                    )
                },
            ),
        )
    }

    // What's actually left between the top row and the panel, measured
    // rather than assumed — the panel's height depends on what the walk is
    // doing, so a guess would be wrong exactly when the window is tightest.
    val railHeightDp = windowHeightDp -
        with(density) { (panelHeightPx + claimCardSpacePx + topBarHeightPx).toDp().value.toInt() } -
        RAIL_MARGIN_DP -
        COMPASS_CLEARANCE_DP
    val layout = WindowLayoutPolicy.placeControls(controls.map { it.control }, railHeightDp)
    fun placed(where: List<MapControl>) = controls.filter { it.control in where }


    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        EncloseMap(
            walk = walk,
            territories = territories,
            // Any grant will do for the blue dot: a vague position is still worth
            // drawing, even where it is far too vague to claim ground with.
            hasLocationPermission = location.hasPermission,
            controller = controller,
            home = home,
            // Tapped points place themselves; a camera that chases them moves the
            // map out from under the finger placing the next one.
            followWalker = !testMode,
            selected = selectedTerritory,
            onMapTap = { point ->
                if (testMode) {
                    viewModel.addTestPoint(point)
                    true
                } else {
                    // A tap on open ground clears the selection, which is the
                    // same gesture read the other way round.
                    val hit = TerritoryHit.at(point = point, territories = territories)
                    onSelectClaim(hit?.id)
                    // Not while the map is keeping up with a walk in progress:
                    // the next fix would pull the camera straight back, so the
                    // centring would be a lurch and nothing more. The card still
                    // appears, and panning away — which is what following reads
                    // as "look somewhere else" — makes the next tap centre.
                    if (hit != null && !(walk.isTracking && controller.followUser)) {
                        // Bring the claim to the middle of the map the user can
                        // see, not the middle of the window: the panel and the
                        // card about to appear own the foot of the screen. The
                        // card's height is the last one measured rather than
                        // the one currently on screen — there isn't one yet at
                        // this point, and half a card's height only matters on
                        // the very first selection of a session.
                        controller.centerOn(
                            point = Geo.centroid(SnapDisplay.pointsFor(hit)),
                            bottomInsetPx = panelHeightPx + claimCardHeightPx +
                                with(density) { CLAIM_CARD_GAP.roundToPx() },
                        )
                    }
                    hit != null
                }
            },
            bottomInsetPx = panelHeightPx + claimCardSpacePx,
            topInsetPx = topBarHeightPx,
            basemap = basemapStyle,
            // Read once per composition: stable while the map lives, refreshed
            // when a rotation rebuilds it.
            initialCamera = remember { viewModel.lastCamera() },
            onCameraIdle = viewModel::saveCamera,
            modifier = Modifier.fillMaxSize(),
        )

        // The basemap is blank while tiles load; say so rather than showing grey.
        AnimatedVisibility(
            visible = !controller.isStyleLoaded,
            enter = fadeIn(),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            MapLoadingIndicator()
        }

        // --- Top row: claims count, menu, profile -----------------------------
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // Measured before the insets/padding modifiers so the reported
                // height covers everything the map must stay clear of.
                .onSizeChanged { topBarHeightPx = it.height }
                // Top and sides: in landscape the cutout is on the *side*, where
                // statusBarsPadding alone left the claims chip under it.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MapChip(
                icon = Icons.Filled.Flag,
                text = if (territories.isEmpty()) {
                    stringResource(R.string.map_no_claims)
                } else {
                    stringResource(
                        R.string.map_claims_summary,
                        territories.size,
                        res.formatArea(territories.sumOf { it.areaSqMeters }),
                    )
                },
                contentDescription = stringResource(R.string.map_open_territories),
                onClick = { showList = true },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No overflow menu of app settings any more: test mode, GPX
                // import and the explainer live on the profile screen, behind
                // the avatar. What can still appear here is map controls that
                // had nowhere to sit — and only then, so an empty ⋮ never takes
                // up the corner.
                if (layout.menu.isNotEmpty()) {
                    Box {
                        MapControlButton(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.map_more_controls),
                            onClick = { showMenu = true },
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            // Hold gestures (resetting home, disarming the
                            // floating window) don't survive the trip to a menu
                            // item — they're back on the button as soon as the
                            // window has room for it.
                            placed(layout.menu).forEach { spec ->
                                DropdownMenuItem(
                                    text = { Text(spec.label) },
                                    enabled = spec.enabled,
                                    onClick = {
                                        showMenu = false
                                        spec.onClick()
                                    },
                                    leadingIcon = { Icon(spec.icon, null, tint = spec.tint) },
                                )
                            }
                        }
                    }
                }

                ProfileAvatarButton(
                    initials = profile.profile?.initials ?: "?",
                    onClick = onOpenProfile,
                )
            }
        }

        // --- Right rail: the map's controls, sitting above the panel ----------
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(end = 12.dp)
                // The panel's measured height already includes its own bottom
                // inset, so the rail must not add one as well.
                .padding(bottom = panelHeight + claimCardSpace + 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            placed(layout.right).forEach { spec ->
                MapControlButton(
                    icon = spec.icon,
                    contentDescription = spec.label,
                    enabled = spec.enabled,
                    tint = spec.tint,
                    onLongPress = spec.onLongPress,
                    longPressLabel = spec.longPressLabel,
                    onClick = spec.onClick,
                )
            }
        }

        // --- Left rail: zoom, once the right one has run out of room ----------
        // Raised clear of the bottom-left corner, which carries MapLibre's logo
        // and the OpenStreetMap attribution — a licence requirement, so it can't
        // be covered by controls that had nowhere else to go.
        if (layout.left.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(start = 12.dp)
                    .padding(bottom = panelHeight + claimCardSpace + ORNAMENT_CLEARANCE),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                placed(layout.left).forEach { spec ->
                    MapControlButton(
                        icon = spec.icon,
                        contentDescription = spec.label,
                        enabled = spec.enabled,
                        tint = spec.tint,
                        onClick = spec.onClick,
                    )
                }
            }
        }

        // The claim under the last tap, named where the finger already is.
        // Anchored to the panel like the snackbar: a card that names a claim is
        // no use if the controls are sitting on top of it. It survives a trip
        // to the detail screen and back, so returning lands on the same claim,
        // still highlighted.
        selectedTerritory?.let { territory ->
            SelectedClaimCard(
                territory = territory,
                onOpen = { onOpenTerritory(territory.id) },
                onDismiss = { onSelectClaim(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    )
                    .padding(bottom = panelHeight + CLAIM_CARD_GAP)
                    .padding(horizontal = 12.dp)
                    // Innermost, so what is measured is the card itself — the
                    // paddings above it are the space it is being held clear of,
                    // and counting them here would raise the rails by the height
                    // of the panel a second time.
                    .onSizeChanged { claimCardHeightPx = it.height },
            )
        }

        // The snackbar sits directly above the panel — anchored to the panel's
        // measured height so an "Undo" action is never covered.
        SnackbarHost(
            snackbarHost,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = panelHeight + claimCardSpace + 8.dp)
                .padding(horizontal = 12.dp),
        )

        // Capped and centred: a panel stretched across a landscape phone puts
        // the Start button an inch from the figures it belongs to, and on a
        // tablet it's a metre of empty card.
        ControlPanel(
            walk = walk,
            testMode = testMode,
            injectedWalk = injectedWalk,
            activityType = activityType,
            onSelectActivity = viewModel::setActivityType,
            location = location,
            onStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    // Ask first rather than start: in test mode the GPS service
                    // never runs, so a walk begun here follows map taps and
                    // records nothing of where the user actually goes. That is
                    // indistinguishable from a working walk until Stop.
                    testMode -> confirmTestWalk = true
                    // Guarded rather than trusted: PanelSummary only offers Start
                    // when location is ready, and starting a walk that cannot
                    // record is the failure this whole path exists to stop.
                    location.canRecord -> viewModel.startWalk()
                    else -> onRequestPermission()
                }
            },
            onClaim = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.stopWalk()
            },
            onFinishWithoutClaim = { confirmDiscardWalk = true },
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            onOpenLocationSettings = onOpenLocationSettings,
            onHowItWorks = viewModel::openHowItWorks,
            collapsed = collapsePanel,
            foldable = WindowLayoutPolicy.panelFoldable(windowHeightDp),
            onCollapsedChange = { collapsed ->
                // The user has said what they want the panel to do, so the
                // automatic fold steps out of the way for the rest of this walk.
                autoCollapsed = false
                viewModel.setPanelCollapsed(collapsed)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = PANEL_MAX_WIDTH)
                // Measured outside the insets/margins so panelHeight is the full
                // space the panel occupies at the bottom of the screen.
                .onSizeChanged { panelHeightPx = it.height }
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }

    pendingClaim?.let { pending ->
        ClaimDialog(
            pending = pending,
            onClaim = { name, color ->
                viewModel.confirmClaim(name, color)
            },
            onDiscard = viewModel::discardClaim,
        )
    }

    voidedWalk?.let { reason ->
        NoticeDialog(
            title = stringResource(R.string.void_title),
            message = when (reason) {
                VoidReason.VEHICLE -> pluralStringResource(
                    R.plurals.void_vehicle,
                    MotionGate.MAX_STRIKES,
                    MotionGate.MAX_STRIKES,
                )
                VoidReason.TOO_FAST -> pluralStringResource(
                    R.plurals.void_too_fast,
                    MotionGate.MAX_STRIKES,
                    MotionGate.MAX_STRIKES,
                )
                VoidReason.UNVERIFIED_GAP -> stringResource(R.string.void_gap)
            },
            onDismiss = viewModel::dismissVoidedWalk,
        )
    }

    // The recorder couldn't run. Nothing was walked and nothing was thrown away —
    // this says so out loud, where it used to be a service that stopped itself in
    // silence behind a panel still claiming to record.
    recordingFailure?.let { failure ->
        val recorded = walk.path.isNotEmpty()
        NoticeDialog(
            title = stringResource(
                if (recorded) R.string.recording_stopped_title else R.string.recording_failed_title,
            ),
            message = listOf(
                stringResource(
                    when (failure) {
                        RecordingFailure.PERMISSION -> R.string.recording_no_permission
                        RecordingFailure.UNAVAILABLE -> R.string.recording_unavailable
                    },
                ),
                // Never quietly dropped: the ground already walked is real, and
                // Stop can still claim it.
                stringResource(if (recorded) R.string.recording_kept else R.string.recording_nothing),
            ).joinToString(" "),
            onDismiss = viewModel::dismissRecordingFailure,
        )
    }

    // Go and look at what was just imported. A track from anywhere but the
    // current view lands off camera, and an import you can't see is
    // indistinguishable from one that didn't happen.
    LaunchedEffect(gpxImport, controller.isStyleLoaded) {
        val done = gpxImport as? GpxImport.Done ?: return@LaunchedEffect
        if (controller.isStyleLoaded && done.route.isNotEmpty()) controller.fitTo(done.route)
    }

    GpxImportDialogs(gpxImport, onDismiss = viewModel::dismissGpxImport)
    BackupDialogs(backupJob, onDismiss = viewModel::dismissBackup)

    confirmSetHome?.let { here ->
        ConfirmDialog(
            title = stringResource(R.string.home_set_title),
            message = stringResource(R.string.home_set_body),
            confirmLabel = stringResource(R.string.home_set_confirm),
            onConfirm = {
                confirmSetHome = null
                viewModel.setHome(here)
                controller.flyTo(here)
                scope.launch { snackbarHost.showSnackbar(res.getString(R.string.map_home_set_snackbar)) }
            },
            onDismiss = { confirmSetHome = null },
        )
    }

    if (confirmResetHome) {
        ConfirmDialog(
            title = stringResource(R.string.home_reset_title),
            message = stringResource(R.string.home_reset_body),
            confirmLabel = stringResource(R.string.home_reset_confirm),
            destructive = true,
            onConfirm = {
                confirmResetHome = false
                viewModel.clearHome()
                scope.launch {
                    snackbarHost.showSnackbar(res.getString(R.string.map_home_cleared_snackbar))
                }
            },
            onDismiss = { confirmResetHome = false },
        )
    }

    // The request either changed the window within a moment or the device
    // ignored it. Nothing else can tell those apart, so the window itself is the
    // answer — compared against the state it was asked from, so re-pairing an
    // existing split is judged the same way as entering one.
    val currentlyMultiWindow by rememberUpdatedState(inMultiWindow)
    LaunchedEffect(splitRequestedAt) {
        if (splitRequestedAt == 0L) return@LaunchedEffect
        delay(SPLIT_SETTLE_MS)
        if (currentlyMultiWindow == splitRequestedFrom) showSplitHelp = true
    }

    if (showSplitHelp) {
        NoticeDialog(
            title = stringResource(R.string.split_title),
            message = listOf(
                stringResource(
                    if (splitRequestedFrom) R.string.split_rebuild_help else R.string.split_enter_help,
                ),
                stringResource(R.string.split_keeps_recording),
            ).joinToString(" "),
            onDismiss = { showSplitHelp = false },
        )
    }

    if (floatingRefused) {
        NoticeDialog(
            title = stringResource(R.string.float_refused_title),
            message = stringResource(R.string.float_refused_body),
            onDismiss = { floatingRefused = false },
        )
    }

    if (noFixForHome) {
        NoticeDialog(
            title = stringResource(R.string.home_no_fix_title),
            message = stringResource(R.string.home_no_fix_body),
            onDismiss = { noFixForHome = false },
        )
    }

    if (confirmDiscardWalk) {
        ConfirmDialog(
            title = stringResource(
                walk.activityType.pick(
                    R.string.discard_walk_title,
                    R.string.discard_run_title,
                    R.string.discard_ride_title,
                ),
            ),
            message = stringResource(R.string.discard_body),
            confirmLabel = stringResource(
                walk.activityType.pick(
                    R.string.panel_discard_walk,
                    R.string.panel_discard_run,
                    R.string.panel_discard_ride,
                ),
            ),
            destructive = true,
            onConfirm = {
                confirmDiscardWalk = false
                viewModel.cancelWalk()
            },
            onDismiss = { confirmDiscardWalk = false },
        )
    }

    if (confirmTestWalk) {
        TestWalkWarningDialog(
            activityType = walk.activityType,
            onStartTestWalk = {
                confirmTestWalk = false
                viewModel.startWalk()
            },
            onLeaveTestMode = {
                confirmTestWalk = false
                viewModel.setTestMode(false)
                // Straight into the real walk they were asking for. Location can
                // be un-ready here in a way it never is on the branch above —
                // test mode is exactly the state in which the panel offers Start
                // without checking — so it goes back through the same guard.
                if (location.canRecord) viewModel.startWalk() else onRequestPermission()
            },
            onDismiss = { confirmTestWalk = false },
        )
    }

    if (showList) {
        TerritoryListSheet(
            territories = territories,
            // A claim shares its id with the walk that made it, so the walk's
            // climb is the claim's climb.
            climbById = walksById.mapValues { (_, w) -> w.elevationGainMeters },
            sort = territorySort,
            onSortChange = viewModel::setTerritorySort,
            onDismiss = { showList = false },
            onSelect = { territory ->
                showList = false
                onOpenTerritory(territory.id)
            },
            onShowOnMap = { territory ->
                showList = false
                controller.fitTo(SnapDisplay.pointsFor(territory))
            },
            onRename = viewModel::renameTerritory,
            onDelete = { territory ->
                showList = false
                deleteWithUndo(territory)
            },
        )
    }

    if (showHowItWorks) {
        HowItWorksSheet(onDismiss = viewModel::dismissHowItWorks)
    }
}

/** Round avatar in the top-right that opens the profile screen. */
@Composable
private fun ProfileAvatarButton(initials: String, onClick: () -> Unit) {
    MapSurface(
        modifier = Modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.map_open_profile), role = Role.Button, onClick = onClick),
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            InitialsAvatar(initials = initials, size = 36.dp)
        }
    }
}

@Composable
private fun MapLoadingIndicator() {
    MapSurface(shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The claim a map tap picked out: what it is, and the way into it.
 *
 * Deliberately a card and not a sheet. A tap on the map is a question about the
 * ground under the finger — "whose is this, how big was it?" — and answering it
 * by covering half the map with the very thing being asked about is the wrong
 * trade. Everything else about the claim is one more tap away, on the detail
 * screen this opens.
 */
@Composable
private fun SelectedClaimCard(
    territory: Territory,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val now = rememberNow()
    // Area first, then where and when — the same order the list rows use, so a
    // claim reads the same wherever it is met.
    val summary = listOfNotNull(
        res.formatArea(territory.areaSqMeters),
        territory.city.takeIf { it.isNotBlank() },
        res.formatRelativeDay(territory.claimedAtEpochMs, now),
    ).joinToString(" · ")

    MapSurface(
        modifier = modifier.widthIn(max = PANEL_MAX_WIDTH),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.map_open_claim, territory.name),
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The claim's own colour, which is what identifies it on the map.
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(hexColor(territory.colorHex)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    territory.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Tapping open ground clears the selection too; this is for the
            // times the ground you want to tap is another claim.
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.map_clear_selection),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Bottom control panel ----------------------------------------------------


@Composable
private fun ControlPanel(
    walk: TrackingManager.WalkState,
    testMode: Boolean,
    /**
     * The walk in progress is fed by injected points rather than GPS. Separate
     * from [testMode]: an imported track replays in every build, test mode or
     * not, and the GPS read-outs have to stand down for it just the same.
     */
    injectedWalk: Boolean,
    activityType: ActivityType,
    onSelectActivity: (ActivityType) -> Unit,
    location: LocationReadiness,
    onStart: () -> Unit,
    onClaim: () -> Unit,
    onFinishWithoutClaim: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onHowItWorks: () -> Unit,
    /** Minimised to a single row, so the map isn't half-covered. */
    collapsed: Boolean,
    /** False in a window too short for the expanded panel to be an option. */
    foldable: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val summary = PanelSummary.of(walk, testMode, location)
    // Beep + buzz once, the moment the loop becomes claimable, so the user knows
    // without looking at the screen. Keyed on readyToClose → fires on each
    // false→true transition.
    val context = LocalContext.current
    LaunchedEffect(walk.readyToClose) {
        if (walk.readyToClose) readyToCloseCue(context)
    }
    // When the loop is ready, flow the same gradient border used by the claim
    // modal to invite the user to press Close loop.
    val readyBorder = if (walk.readyToClose) {
        Modifier.border(BorderStroke(2.5.dp, rememberFlowingGradient()), shape)
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(readyBorder),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        if (collapsed) {
            CollapsedPanel(
                summary = summary,
                walk = walk,
                activityType = activityType,
                onStart = onStart,
                onClaim = onClaim,
                onFinishWithoutClaim = onFinishWithoutClaim,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
                onOpenLocationSettings = onOpenLocationSettings,
                onExpand = if (foldable) ({ onCollapsedChange(false) }) else null,
            )
            return@Card
        }

        Column(
            Modifier.padding(horizontal = 18.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // A full-width strip rather than a corner button: it's the whole top
            // edge of the panel, so minimising never costs a careful tap, and the
            // chevron reads as "this thing folds" without a label.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        onClickLabel = stringResource(R.string.panel_minimise),
                        role = Role.Button,
                        onClick = { onCollapsedChange(true) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.panel_minimise),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            when (summary.status) {
                PanelStatus.TRACKING, PanelStatus.BLOCKED, PanelStatus.READY -> {
                    LiveStats(walk, injected = injectedWalk)
                    WalkActions(
                        walk = walk,
                        onClaim = onClaim,
                        onFinishWithoutClaim = onFinishWithoutClaim,
                    )
                }

                // Location is the whole point of the app, so an explicit, actionable
                // recovery path replaces the old one-line red warning.
                PanelStatus.NO_LOCATION -> LocationBlock(
                    location = location,
                    action = summary.action,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenLocationSettings = onOpenLocationSettings,
                )

                PanelStatus.IDLE -> {
                    IdleBlock(onHowItWorks = onHowItWorks)
                    ActivitySelector(selected = activityType, onSelect = onSelectActivity)
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = PillShape,
                    ) {
                        ButtonContent(
                            Icons.Filled.PlayArrow,
                            stringResource(
                                activityType.pick(
                                    R.string.panel_start_walk,
                                    R.string.panel_start_run,
                                    R.string.panel_start_ride,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The panel folded down to one row: what the walk is doing, and the single
 * action that matters right now.
 *
 * The expanded panel is most of a phone screen, and a map you can only see the
 * top half of is a poor map — but the walk's own controls can never be the thing
 * that's hidden, so the primary action rides along in the collapsed row rather
 * than being something you have to expand to reach.
 */
@Composable
private fun CollapsedPanel(
    summary: PanelSummary,
    walk: TrackingManager.WalkState,
    activityType: ActivityType,
    onStart: () -> Unit,
    onClaim: () -> Unit,
    onFinishWithoutClaim: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    /** Null where the window is too short for the panel to expand into. */
    onExpand: (() -> Unit)?,
) {
    val res = LocalResources.current
    val accents = LocalEncloseAccents.current
    // Ticks so the elapsed time in the collapsed row keeps up with the expanded
    // one; the stats are the reason to look at it at all.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(walk.isTracking) {
        while (walk.isTracking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = walk.startedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L

    val dotColor = when (summary.status) {
        PanelStatus.BLOCKED, PanelStatus.NO_LOCATION -> MaterialTheme.colorScheme.error
        PanelStatus.READY -> accents.success
        PanelStatus.TRACKING -> accents.trail
        PanelStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (summary.status) {
        PanelStatus.IDLE -> stringResource(R.string.panel_idle_title)
        PanelStatus.NO_LOCATION -> when (summary.action) {
            PanelAction.OPEN_LOCATION_SETTINGS -> stringResource(R.string.location_title_off)
            else -> stringResource(R.string.location_title_needed)
        }
        PanelStatus.BLOCKED -> stringResource(R.string.panel_title_paused)
        PanelStatus.READY -> stringResource(R.string.panel_title_ready)
        PanelStatus.TRACKING -> stringResource(walk.activityType.activeLabelRes)
    }
    val detail = when (summary.status) {
        PanelStatus.IDLE -> stringResource(activityType.labelRes)
        PanelStatus.NO_LOCATION -> null
        else -> stringResource(
            R.string.panel_detail_stats,
            res.formatDistance(walk.distanceMeters),
            formatElapsed(elapsedMs),
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        CollapsedAction(
            summary = summary,
            activityType = activityType,
            onStart = onStart,
            onClaim = onClaim,
            onFinishWithoutClaim = onFinishWithoutClaim,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            onOpenLocationSettings = onOpenLocationSettings,
        )
        if (onExpand != null) {
            IconButton(onClick = onExpand, modifier = Modifier.size(TOUCH_TARGET)) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.panel_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The single button the collapsed row leads with, per [PanelSummary.action]. */
@Composable
private fun CollapsedAction(
    summary: PanelSummary,
    activityType: ActivityType,
    onStart: () -> Unit,
    onClaim: () -> Unit,
    onFinishWithoutClaim: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val label = when (summary.action) {
        PanelAction.START -> stringResource(activityType.labelRes)
        PanelAction.CLAIM -> stringResource(R.string.panel_action_claim)
        PanelAction.END -> stringResource(R.string.panel_action_end)
        PanelAction.GRANT_PERMISSION -> stringResource(R.string.panel_action_grant)
        PanelAction.OPEN_SETTINGS, PanelAction.OPEN_LOCATION_SETTINGS ->
            stringResource(R.string.panel_action_settings)
    }
    val onClick = when (summary.action) {
        PanelAction.START -> onStart
        PanelAction.CLAIM -> onClaim
        PanelAction.END -> onFinishWithoutClaim
        PanelAction.GRANT_PERMISSION -> onRequestPermission
        PanelAction.OPEN_SETTINGS -> onOpenAppSettings
        PanelAction.OPEN_LOCATION_SETTINGS -> onOpenLocationSettings
    }
    // Ending a walk is destructive and must not wear the inviting colour, exactly
    // as in the expanded panel.
    val colors = if (summary.action == PanelAction.END) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    val icon = when (summary.action) {
        PanelAction.START -> Icons.Filled.PlayArrow
        PanelAction.CLAIM -> Icons.Filled.Flag
        PanelAction.END -> Icons.Filled.Stop
        PanelAction.GRANT_PERMISSION,
        PanelAction.OPEN_SETTINGS,
        PanelAction.OPEN_LOCATION_SETTINGS,
        -> Icons.Filled.LocationOff
    }
    Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = PillShape,
        colors = colors,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        ButtonContent(icon, label)
    }
}

/**
 * Walk / Run / Bike, inline above the Start button so choosing a mode never
 * costs an extra screen or tap. The choice is remembered, and it only tightens
 * the motion checks — see [ActivityType].
 */
@Composable
private fun ActivitySelector(
    selected: ActivityType,
    onSelect: (ActivityType) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityType.entries.forEach { type ->
            val isSelected = type == selected
            FilterChip(
                selected = isSelected,
                // Shown but not selectable while a mode is turned off: hiding
                // them would make the app look like it only ever did walking,
                // and greyed chips say "not yet" instead.
                enabled = type.available,
                onClick = { onSelect(type) },
                label = { Text(stringResource(type.labelRes)) },
                leadingIcon = {
                    Icon(
                        when (type) {
                            ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
                            ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
                            ActivityType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = PillShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun IdleBlock(onHowItWorks: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.panel_idle_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.panel_idle_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onHowItWorks, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.panel_how_it_works))
        }
    }
}

/**
 * The recovery block shown in place of a Start button when location can't
 * produce a fix worth recording.
 *
 * Each way of being un-ready gets its own sentence and its own button, because
 * they need different things done and pointing at the wrong screen wastes the
 * walk someone came to do. In particular, "granted, but Approximate" and
 * "granted, but location is off" both used to read as fully granted and start a
 * walk that could never record.
 */
@Composable
private fun LocationBlock(
    location: LocationReadiness,
    action: PanelAction,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val title = stringResource(
        when (location) {
            LocationReadiness.SERVICES_OFF -> R.string.location_title_off
            LocationReadiness.APPROXIMATE_ONLY -> R.string.location_title_precise
            else -> R.string.location_title_needed
        },
    )
    val body = stringResource(
        when (location) {
            LocationReadiness.SERVICES_OFF -> R.string.location_body_off
            LocationReadiness.APPROXIMATE_ONLY -> R.string.location_body_precise
            LocationReadiness.BLOCKED -> R.string.location_body_blocked
            else -> R.string.location_body_needed
        },
    )
    val (onClick, labelRes) = when (action) {
        PanelAction.OPEN_LOCATION_SETTINGS ->
            onOpenLocationSettings to R.string.location_open_location_settings
        PanelAction.OPEN_SETTINGS -> onOpenAppSettings to R.string.location_open_settings
        else -> onRequestPermission to R.string.location_grant
    }
    val label = stringResource(labelRes)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = PillShape,
    ) {
        Text(label)
    }
}

@Composable
private fun WalkActions(
    walk: TrackingManager.WalkState,
    onClaim: () -> Unit,
    onFinishWithoutClaim: () -> Unit,
) {
    if (walk.readyToClose) {
        // Ready: one obvious, rewarding action.
        Button(
            onClick = onClaim,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = PillShape,
        ) {
            ButtonContent(Icons.Filled.Flag, stringResource(R.string.panel_close_and_claim))
        }
        TextButton(onClick = onFinishWithoutClaim, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    walk.activityType.pick(
                        R.string.panel_discard_walk,
                        R.string.panel_discard_run,
                        R.string.panel_discard_ride,
                    ),
                ),
            )
        }
    } else {
        // Not ready: stopping throws the walk away, so it must not look like
        // the primary action, and it asks for confirmation.
        Button(
            onClick = onFinishWithoutClaim,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            ButtonContent(
                Icons.Filled.Stop,
                stringResource(
                    walk.activityType.pick(
                        R.string.panel_end_walk,
                        R.string.panel_end_run,
                        R.string.panel_end_ride,
                    ),
                ),
            )
        }
    }
}

/**
 * @param injected the walk's points are being fed in rather than recorded from
 *   GPS — map taps in test mode, or a replayed GPX track. No receiver is running,
 *   so anything reporting on one would describe a device that isn't listening.
 */
@Composable
private fun LiveStats(walk: TrackingManager.WalkState, injected: Boolean) {
    val res = LocalResources.current
    // Tick once a second so elapsed time and pace advance live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(walk.isTracking) {
        while (walk.isTracking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = walk.startedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
    // Its own clock, because `startedAtMs` is set by the *first accepted fix* —
    // the very thing that never arrives in the case this watches for.
    val startedWaitingMs = remember(walk.isTracking) { System.currentTimeMillis() }
    val fixWarning = FixWatch.warning(
        isTracking = walk.isTracking,
        recordedPoints = walk.path.size,
        accuracyMeters = walk.accuracyMeters,
        waitingMs = (now - startedWaitingMs).coerceAtLeast(0L),
    )
    val accents = LocalEncloseAccents.current
    val blocked = walk.motionBlocked

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (blocked) MaterialTheme.colorScheme.error else accents.trail),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (blocked) R.string.stats_paused else walk.activityType.activeLabelRes,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // No GPS is involved in an injected walk, so don't report on it — the
        // chip would sit on "acquiring…" for as long as the walk lasted.
        if (!injected) GpsAccuracyIndicator(walk.accuracyMeters)
    }

    // Five figures over two rows rather than one: at 20sp a fifth column leaves
    // about 68dp, which clips values like "8:20 /km" instead of ellipsing them.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Equal weights: the figures keep their columns as digits change.
        Metric(
            label = stringResource(R.string.stats_distance),
            value = res.formatDistance(walk.distanceMeters),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = stringResource(R.string.stats_time),
            value = formatElapsed(elapsedMs),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = stringResource(R.string.stats_pace),
            value = res.formatPace(walk.distanceMeters, elapsedMs),
            modifier = Modifier.weight(1f),
        )
    }
    // A sibling row, not a nested one: the panel's Column spaces its children.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Metric(
            label = stringResource(R.string.stats_climb),
            value = res.formatClimb(walk.elevationGainMeters),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = stringResource(R.string.stats_from_start),
            value = walk.distanceToStartMeters?.let { res.formatDistance(it) } ?: EM_DASH,
            modifier = Modifier.weight(1f),
        )
        // Holds the third column so the two rows line up as a grid instead of
        // the lower pair drifting between the columns above it.
        Spacer(Modifier.weight(1f))
    }

    // Nothing has been recorded and it has stopped being plausible to call that
    // warm-up. This outranks the loop checklist for the same reason the blocked
    // notice does: a progress bar towards a loop is meaningless when no part of
    // the route is being kept.
    when {
        blocked -> MotionBlockedNotice(walk)
        fixWarning != null -> FixWarningNotice(fixWarning, walk.accuracyMeters)
        else -> LoopProgress(walk)
    }

    // A strike the walk survived. Kept on screen afterwards because the count is
    // what decides the next one: someone who doesn't know they're on their last
    // warning can't act on it.
    if (!blocked && walk.strikes > 0) StrikeNotice(walk)

    // A gap is not a failure and doesn't stop the walk, so it sits below the
    // checklist as a footnote rather than replacing it.
    if (walk.hadSignalGap && !injected) SignalGapNotice()
}

/**
 * Shown for the rest of the walk once the fixes stopped arriving for a while —
 * a dozing device, a tunnel, a long stretch with the screen off.
 *
 * The walk deliberately survives this. Losing the signal is the device's doing,
 * not the walker's, and discarding an hour on foot over it is the worse error by
 * a wide margin. What the app owes the user instead is the truth: the route now
 * contains a straight line across ground it never saw.
 */
@Composable
private fun SignalGapNotice() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.notice_signal_gap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when Start is pressed with test mode on (a debug build only).
 *
 * Test mode skips the location service entirely, so the walk it begins follows
 * map taps and records nothing of where the user actually goes — and nothing on
 * the panel afterwards distinguishes it from a real one until Stop, by which
 * time the route is unrecoverable. The way out is offered beside the way on,
 * because someone who pressed Start on a map almost always meant the real thing.
 */
@Composable
private fun TestWalkWarningDialog(
    activityType: ActivityType,
    onStartTestWalk: () -> Unit,
    onLeaveTestMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.test_walk_title)) },
        text = {
            Text(
                stringResource(
                    activityType.pick(
                        R.string.test_walk_body_walk,
                        R.string.test_walk_body_run,
                        R.string.test_walk_body_ride,
                    ),
                ),
            )
        },
        confirmButton = {
            Button(onClick = onLeaveTestMode) { Text(stringResource(R.string.test_walk_leave)) }
        },
        dismissButton = {
            TextButton(onClick = onStartTestWalk) {
                Text(
                    stringResource(
                        activityType.pick(
                            R.string.test_walk_start_walk,
                            R.string.test_walk_start_run,
                            R.string.test_walk_start_ride,
                        ),
                    ),
                )
            }
        },
    )
}

/**
 * How the GPX import is going, wherever the user happens to be standing.
 *
 * The import is started from the profile screen but runs on the ViewModel's
 * scope, so it outlives that screen — and an import you can't see the progress
 * of is indistinguishable from one that has hung. Both screens draw this; only
 * one of them is composed at a time.
 */
@Composable
internal fun GpxImportDialogs(state: GpxImport?, onDismiss: () -> Unit) {
    when (state) {
        null -> Unit
        is GpxImport.Reading -> GpxProgressDialog(
            label = stringResource(R.string.import_reading),
            progress = null,
        )
        is GpxImport.Replaying -> GpxProgressDialog(
            label = pluralStringResource(R.plurals.import_replaying, state.total, state.done, state.total),
            progress = if (state.total == 0) null else state.done.toFloat() / state.total,
        )

        is GpxImport.Done -> NoticeDialog(
            title = stringResource(R.string.import_done_title),
            message = "${state.headline}\n\n${state.detail}",
            onDismiss = onDismiss,
        )

        is GpxImport.Failed -> NoticeDialog(
            title = stringResource(R.string.import_failed_title),
            message = state.reason,
            onDismiss = onDismiss,
        )
    }
}

/**
 * How a backup or a restore is going — drawn by both screens for the reason
 * [GpxImportDialogs] is: the work runs on the ViewModel's scope and outlives the
 * profile screen it was started from, and a restore whose report nobody ever sees
 * is a restore the user has no reason to believe happened.
 *
 * The progress dialog takes no dismissal. An export is reading every table while
 * the file is open for writing, and a restore is inside a transaction; letting
 * the user walk away mid-way and start something else is how you get half a
 * backup that looks like a whole one.
 */
@Composable
internal fun BackupDialogs(state: BackupJob?, onDismiss: () -> Unit) {
    when (state) {
        null -> Unit
        is BackupJob.Exporting -> GpxProgressDialog(
            title = stringResource(R.string.backup_exporting_title),
            label = stringResource(R.string.backup_exporting),
            progress = null,
        )

        is BackupJob.Importing -> GpxProgressDialog(
            title = stringResource(R.string.backup_importing_title),
            label = stringResource(R.string.backup_importing),
            progress = null,
        )

        is BackupJob.Done -> NoticeDialog(
            title = stringResource(R.string.backup_done_title),
            message = "${state.headline}\n\n${state.detail}",
            onDismiss = onDismiss,
        )

        is BackupJob.Failed -> NoticeDialog(
            title = stringResource(R.string.backup_failed_title),
            message = state.reason,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Import in progress. Deliberately has no dismiss button and ignores the scrim:
 * the replay is feeding the tracker, and letting the user start tapping points
 * into the middle of it would interleave two routes into one walk.
 *
 * Determinate once the point count is known — a bar that fills is the difference
 * between "working" and "hung" on a long track — and indeterminate while the
 * file is still being read, when there is genuinely nothing to count.
 */
@Composable
internal fun GpxProgressDialog(
    label: String,
    progress: Float?,
    title: String = stringResource(R.string.import_title),
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (progress == null) {
                    CircularProgressIndicator(Modifier.size(36.dp))
                } else {
                    // Not ProgressTrack: its 500 ms smoothing is right for the
                    // loop checklist, which changes a few times a walk, and
                    // wrong here — the replay updates every few milliseconds, so
                    // the animation never catches up and the bar reads far
                    // behind the count printed under it.
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Shown when a walk has been running long enough that "acquiring…" has stopped
 * being a plausible explanation for an empty path — see [FixWatch].
 *
 * The two cases are worded apart on purpose. Telling someone to move into the
 * open when the real problem is approximate-only location sends them on a walk
 * that still records nothing, which is precisely the failure this exists to end.
 */
@Composable
private fun FixWarningNotice(warning: FixWarning, accuracyMeters: Float?) {
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = error.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, error.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOff,
                    contentDescription = null,
                    tint = error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when (warning) {
                            FixWarning.NO_FIX -> R.string.fix_no_fix_title
                            FixWarning.TOO_VAGUE -> R.string.fix_too_vague_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (warning) {
                    FixWarning.NO_FIX -> stringResource(R.string.fix_no_fix_body)
                    FixWarning.TOO_VAGUE -> {
                        val limit = TrackingManager.MAX_ACCURACY_METERS.roundToInt()
                        if (accuracyMeters != null) {
                            stringResource(
                                R.string.fix_too_vague_body_current,
                                limit,
                                accuracyMeters.roundToInt(),
                            )
                        } else {
                            stringResource(R.string.fix_too_vague_body, limit)
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when the tracker is refusing to record because the movement isn't
 * human-powered. Names the reason, states the consequence, and counts down the
 * grace window so the outcome is never a surprise.
 */
@Composable
private fun MotionBlockedNotice(walk: TrackingManager.WalkState) {
    // Monotonic clock: the countdown must not jump if wall-clock time changes.
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(walk.blockedSinceElapsedMs) {
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val since = walk.blockedSinceElapsedMs ?: nowElapsed
    val remainingMs = (MotionGate.GRACE_MS - (nowElapsed - since)).coerceAtLeast(0L)
    val fraction = (remainingMs.toFloat() / MotionGate.GRACE_MS).coerceIn(0f, 1f)
    val vehicle = walk.blockedReason == BlockReason.VEHICLE
    // The warning being counted down to is the next one, not the last one given.
    val pendingStrike = walk.strikes + 1
    val fatal = pendingStrike >= MotionGate.MAX_STRIKES

    val error = MaterialTheme.colorScheme.error
    // A tint rather than a solid error fill: the End walk button below is already
    // solid red, and two blocks of it flatten the panel's hierarchy.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = error.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, error.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (vehicle) Icons.Filled.DirectionsCar else Icons.Filled.Speed,
                    contentDescription = null,
                    tint = error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        if (vehicle) {
                            R.string.motion_vehicle_title
                        } else {
                            walk.activityType.pick(
                                R.string.motion_too_fast_walk,
                                R.string.motion_too_fast_run,
                                R.string.motion_too_fast_ride,
                            )
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    walk.activityType.pick(
                        R.string.motion_body_walk,
                        R.string.motion_body_run,
                        R.string.motion_body_ride,
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ProgressTrack(
                progress = fraction,
                color = error,
                trackColor = error.copy(alpha = 0.18f),
                thickness = 6.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (fatal) {
                    // Last one: say what actually happens, not "warning 3 of 3".
                    stringResource(
                        walk.activityType.pick(
                            R.string.motion_last_warning_walk,
                            R.string.motion_last_warning_run,
                            R.string.motion_last_warning_ride,
                        ),
                        (remainingMs / 1000).toInt(),
                    )
                } else {
                    stringResource(
                        R.string.motion_warning_countdown,
                        pendingStrike,
                        MotionGate.MAX_STRIKES,
                        (remainingMs / 1000).toInt(),
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown for the rest of the walk once a warning has been used and the walk has
 * carried on.
 *
 * The count is the point. Strikes exist so one bad stretch doesn't end an
 * outing, but that only helps if the user can see how much rope is left — and
 * being told about the third one only as it lands is the version of this that
 * feels arbitrary.
 */
@Composable
private fun StrikeNotice(walk: TrackingManager.WalkState) {
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = error.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (walk.strikesRemaining == 1) {
                    stringResource(
                        walk.activityType.pick(
                            R.string.strike_last_walk,
                            R.string.strike_last_run,
                            R.string.strike_last_ride,
                        ),
                        walk.strikes,
                        MotionGate.MAX_STRIKES,
                    )
                } else {
                    pluralStringResource(
                        walk.activityType.pick(
                            R.plurals.strike_left_walk,
                            R.plurals.strike_left_run,
                            R.plurals.strike_left_ride,
                        ),
                        walk.strikesRemaining,
                        walk.strikes,
                        MotionGate.MAX_STRIKES,
                        walk.strikesRemaining,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The three conditions for claiming a loop, as a stepper with a progress bar
 * for whichever step is active.
 *
 * Previously this was a single sentence that changed text as conditions were
 * met, which left users unsure how many hurdles were left or how close they
 * were to the next one.
 */
@Composable
private fun LoopProgress(walk: TrackingManager.WalkState) {
    val accents = LocalEncloseAccents.current
    val leaveRadius = TrackingManager.leaveStartRadiusMeters
    val minPerimeter = TrackingManager.minPerimeterMeters
    val closureRadius = TrackingManager.closureRadiusMeters
    val toStart = walk.distanceToStartMeters ?: 0.0

    val steps = listOf(
        LoopStep(
            label = stringResource(R.string.loop_step_leave),
            done = walk.hasLeftStart,
            progress = (toStart / leaveRadius).toFloat(),
        ),
        LoopStep(
            label = stringResource(R.string.loop_step_cover, minPerimeter.roundToInt()),
            done = walk.distanceMeters >= minPerimeter,
            progress = (walk.distanceMeters / minPerimeter).toFloat(),
        ),
        LoopStep(
            label = stringResource(R.string.loop_step_return),
            done = walk.readyToClose,
            // Approaches 1 as the walker closes on the closing radius.
            progress = if (walk.canCloseLoop && toStart > 0) {
                (closureRadius / toStart).toFloat()
            } else {
                0f
            },
        ),
    )
    val activeIndex = steps.indexOfFirst { !it.done }.let { if (it == -1) steps.lastIndex else it }
    val active = steps[activeIndex]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEachIndexed { index, step ->
                StepChip(
                    step = step,
                    isActive = index == activeIndex && !step.done,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ProgressTrack(
            progress = if (walk.readyToClose) 1f else active.progress,
            color = if (walk.readyToClose) accents.success else MaterialTheme.colorScheme.primary,
            thickness = 6.dp,
        )
        Text(
            loopHint(walk),
            style = MaterialTheme.typography.bodyMedium,
            color = if (walk.readyToClose) accents.success
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class LoopStep(val label: String, val done: Boolean, val progress: Float)

@Composable
private fun StepChip(step: LoopStep, isActive: Boolean, modifier: Modifier = Modifier) {
    val accents = LocalEncloseAccents.current
    val container = when {
        step.done -> accents.success.copy(alpha = 0.16f)
        isActive -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        step.done -> accents.success
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = PillShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (step.done) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                step.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Explains exactly what still blocks closing, so it's never a mystery. */
@Composable
private fun loopHint(walk: TrackingManager.WalkState): String {
    val remaining = (TrackingManager.minPerimeterMeters - walk.distanceMeters).roundToInt()
    return when {
        walk.readyToClose -> stringResource(R.string.loop_hint_ready)
        walk.canCloseLoop -> stringResource(
            R.string.loop_hint_head_back,
            walk.distanceToStartMeters?.roundToInt() ?: 0,
        )
        !walk.hasLeftStart -> stringResource(
            R.string.loop_hint_leave,
            TrackingManager.leaveStartRadiusMeters.roundToInt(),
        )
        else -> stringResource(R.string.loop_hint_keep_going, remaining)
    }
}

/**
 * Colored dot + "±Xm" summarizing the current GPS fix quality.
 *
 * Past [TrackingManager.MAX_ACCURACY_METERS] the fix isn't merely poor, it is
 * *discarded* — so the read-out says so. A bare "±800 m" in an unfamiliar colour
 * reads as a weak signal that is nonetheless being recorded, which is the exact
 * misunderstanding that lets a walk run for an hour and keep nothing.
 */
@Composable
private fun GpsAccuracyIndicator(accuracyMeters: Float?) {
    val accents = LocalEncloseAccents.current
    val dot = when {
        accuracyMeters == null -> MaterialTheme.colorScheme.onSurfaceVariant
        accuracyMeters <= 10f -> accents.gpsGood
        accuracyMeters <= 25f -> accents.gpsFair
        else -> accents.gpsPoor
    }
    val label = when {
        accuracyMeters == null -> stringResource(R.string.gps_acquiring)
        accuracyMeters <= TrackingManager.MAX_ACCURACY_METERS ->
            stringResource(R.string.gps_accuracy, accuracyMeters.roundToInt())
        else -> stringResource(R.string.gps_not_recorded, accuracyMeters.roundToInt())
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Claim dialog ------------------------------------------------------------

@Composable
private fun ClaimDialog(
    pending: TrackingManager.PendingClaim,
    onClaim: (String, String) -> Unit,
    onDiscard: () -> Unit,
) {
    val res = LocalResources.current
    // rememberSaveable: the dialog survives rotation with the typed name intact.
    // Keyed on the claim so a second loop starts from its own suggestion.
    var name by rememberSaveable(pending.id) { mutableStateOf(pending.suggestedName) }
    var colorHex by rememberSaveable(pending.id) { mutableStateOf(CLAIM_PALETTE.first()) }
    val accent = hexColor(colorHex)
    val shape = MaterialTheme.shapes.extraLarge

    androidx.compose.ui.window.Dialog(onDismissRequest = onDiscard) {
        Surface(
            modifier = Modifier
                .border(BorderStroke(2.5.dp, rememberFlowingGradient()), shape)
                .imePadding(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Scrolls so the actions stay reachable on short screens and
                    // with the keyboard open.
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Hero badge with a soft accent glow.
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.32f), Color.Transparent),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f))
                            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Flag,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.claim_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.claim_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))
                // Two tiles, not three: inside a dialog three columns are too
                // narrow for values like "38063 m²" and truncate them.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatTile(
                        label = stringResource(R.string.claim_area),
                        value = res.formatArea(pending.areaSqMeters),
                        modifier = Modifier.weight(1f),
                        accent = accent,
                    )
                    StatTile(
                        label = stringResource(R.string.claim_perimeter),
                        value = res.formatDistance(pending.perimeterMeters),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Climb rides on the caption rather than becoming a third tile,
                // for the width reason above.
                Text(
                    stringResource(
                        R.string.claim_closed_caption,
                        res.formatDistance(pending.distanceToStartMeters),
                        res.formatClimb(pending.elevationGainMeters),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                // Said plainly rather than hidden: the loop is still claimable —
                // the walking was real — but part of its outline is a straight
                // line drawn across ground the recording never saw.
                if (pending.hadSignalGap) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.claim_signal_gap_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.claim_name_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { name = NameGenerator.random() }) {
                            Icon(
                                Icons.Filled.Casino,
                                contentDescription = stringResource(R.string.claim_suggest_name),
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        cursorColor = accent,
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.claim_color_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ColorPickerRow(selectedHex = colorHex, onSelect = { colorHex = it })

                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.claim_discard))
                    }
                    Button(
                        onClick = { onClaim(name.trim(), colorHex) },
                        modifier = Modifier.weight(1f),
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        ButtonContent(Icons.Filled.Flag, stringResource(R.string.claim_confirm))
                    }
                }
            }
        }
    }
}

// --- Onboarding --------------------------------------------------------------

/**
 * First-run explainer, also reachable from the overflow menu. The core mechanic
 * (walk a loop, come back, claim what's inside) isn't guessable from a map with
 * a Start button, so it gets stated once, plainly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HowItWorksSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.how_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.how_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            HowStep(
                number = 1,
                icon = Icons.Filled.PlayArrow,
                title = stringResource(R.string.how_start_title),
                body = stringResource(R.string.how_start_body),
            )
            HowStep(
                number = 2,
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = stringResource(R.string.how_loop_title),
                body = stringResource(
                    R.string.how_loop_body,
                    TrackingManager.leaveStartRadiusMeters.roundToInt(),
                    TrackingManager.minPerimeterMeters.roundToInt(),
                ),
            )
            HowStep(
                number = 3,
                icon = Icons.Filled.Flag,
                title = stringResource(R.string.how_claim_title),
                body = stringResource(R.string.how_claim_body),
            )

            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.how_own_power),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = PillShape,
            ) {
                Text(stringResource(R.string.how_got_it))
            }
        }
    }
}

@Composable
private fun HowStep(
    number: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.how_step_heading, number, title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of the map's floating controls, as data rather than as a composable.
 *
 * [WindowLayoutPolicy] decides *where* each one is drawn — right rail, left rail
 * or the ⋮ menu — and it can only do that if the controls exist as a list before
 * anything is emitted. Describing them once here is also what keeps a control
 * from behaving differently depending on which of the three it ended up in.
 */
private data class MapControlSpec(
    val control: MapControl,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** Doubles as the TalkBack description on the rail and the menu item's text. */
    val label: String,
    val tint: Color,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** Rail only: a menu item has no hold gesture to hang this on. */
    val onLongPress: (() -> Unit)? = null,
    val longPressLabel: String? = null,
)

/**
 * Room the rails can't use: the 12 dp above the panel plus a little breathing
 * space, so the topmost control doesn't sit tight against the top row.
 */
private const val RAIL_MARGIN_DP = 24

/**
 * Room kept at the top of the right rail for the map's own compass.
 *
 * MapLibre draws it at the top-right, below the top row (see the ornament
 * margins in `EncloseMap`), and the rail grows *upward* from the panel — so in a
 * short window the rail's topmost button climbs straight into it and the two
 * circles overlap. The compass is how you get a rotated map back to north, so it
 * can't be the thing that gives way.
 *
 * 60 dp: a 48 dp ornament plus the 12 dp margin it is placed with. On a
 * full-height window this costs nothing — there is more rail than controls
 * either way — and on a short one it costs the slot the collision was happening
 * in.
 */
private const val COMPASS_CLEARANCE_DP = 60

/**
 * How wide the bottom panel is allowed to get. Beyond roughly this, the figures
 * at one end and the button at the other stop reading as one control — which is
 * what a landscape phone and any tablet would otherwise produce.
 */
/** Breathing room between the selected-claim card and the panel below it. */
private val CLAIM_CARD_GAP = 8.dp

private val PANEL_MAX_WIDTH = 600.dp

/**
 * How far the left rail sits above the panel. Wider than the right rail's 12 dp
 * because the bottom-left corner is MapLibre's logo and the OpenStreetMap
 * attribution, which carries the data credit and has to stay visible and
 * tappable.
 */
private val ORNAMENT_CLEARANCE = 52.dp

/**
 * How long to wait before deciding a split-screen request went nowhere.
 *
 * Long enough for the system's own transition to finish (the window is resized
 * and `onMultiWindowModeChanged` delivered), short enough that the explanation
 * still reads as a response to the tap.
 */
private const val SPLIT_SETTLE_MS = 900L

// --- Cues --------------------------------------------------------------------

/** Beep + vibrate once to signal the loop is ready to close. Fails silently. */
private fun readyToCloseCue(context: android.content.Context) {
    try {
        val tone = android.media.ToneGenerator(
            android.media.AudioManager.STREAM_NOTIFICATION,
            80,
        )
        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
        // Release after the tone finishes so it isn't cut short.
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ tone.release() }, 300)
    } catch (_: Throwable) {
        // No audio available — the haptic still fires below.
    }
    try {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = context.getSystemService(android.os.VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }
        vibrator?.vibrate(
            android.os.VibrationEffect.createOneShot(
                200L,
                android.os.VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
    } catch (_: Throwable) {
        // No vibrator / permission — ignore.
    }
}
