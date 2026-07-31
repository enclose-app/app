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
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.BlockReason
import io.app.enclose.tracking.ActivityType
import io.app.enclose.tracking.MotionGate
import io.app.enclose.tracking.NameGenerator
import io.app.enclose.tracking.VoidReason
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.theme.LocalEncloseAccents
import io.app.enclose.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    /** True when the OS will no longer show a permission prompt. */
    permissionBlocked: Boolean = false,
    /** Opens the system app-settings page, for the blocked-permission case. */
    onOpenAppSettings: () -> Unit = {},
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
    val walk by viewModel.walk.collectAsStateWithLifecycle()
    val territories by viewModel.territories.collectAsStateWithLifecycle()
    val walksById by viewModel.walksById.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val activityType by viewModel.activityType.collectAsStateWithLifecycle()
    val pendingClaim by viewModel.pendingClaim.collectAsStateWithLifecycle()
    val showHowItWorks by viewModel.showHowItWorks.collectAsStateWithLifecycle()
    val voidedWalk by viewModel.voidedWalk.collectAsStateWithLifecycle()
    val gpxImport by viewModel.gpxImport.collectAsStateWithLifecycle()
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

    var showList by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDiscardWalk by remember { mutableStateOf(false) }
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
            snackbarHost.showSnackbar("Claimed ${t.name} · ${formatArea(t.areaSqMeters)}")
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
                message = "Deleted ${territory.name}",
                actionLabel = "Undo",
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
                    label = "Float the walk over other apps",
                    tint = if (floatingWindowEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onLongPress = if (floatingWindowEnabled) {
                        {
                            onSetFloatingWindow(false)
                            scope.launch { snackbarHost.showSnackbar("Floating window off") }
                        }
                    } else {
                        null
                    },
                    longPressLabel = "Stop floating automatically",
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
                        "Rebuild the split screen"
                    } else {
                        "Share the screen with another app"
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
                label = "Zoom in",
                enabled = controller.isStyleLoaded,
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { controller.zoomBy(ZOOM_BUTTON_STEP) },
            ),
        )
        add(
            MapControlSpec(
                control = MapControl.ZOOM_OUT,
                icon = Icons.Filled.Remove,
                label = "Zoom out",
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
                label = if (home == null) "Set your home position" else "Go home",
                // Flying home needs only a map; setting it needs a fix.
                enabled = if (home == null) controller.canLocate else controller.isStyleLoaded,
                tint = if (home == null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                onLongPress = if (home == null) null else ({ confirmResetHome = true }),
                longPressLabel = "Reset your home position",
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
                label = if (controller.followUser) {
                    "Following your location"
                } else {
                    "Recenter and follow your location"
                },
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
                label = if (basemapDark) "Switch to light map" else "Switch to dark map",
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
        with(density) { (panelHeightPx + topBarHeightPx).toDp().value.toInt() } -
        RAIL_MARGIN_DP
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
            hasLocationPermission = hasLocationPermission,
            controller = controller,
            home = home,
            // Tapped points place themselves; a camera that chases them moves the
            // map out from under the finger placing the next one.
            followWalker = !testMode,
            onMapTap = if (testMode) viewModel::addTestPoint else null,
            bottomInsetPx = panelHeightPx,
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
                    "No claims yet"
                } else {
                    "${territories.size} · ${formatArea(territories.sumOf { it.areaSqMeters })}"
                },
                contentDescription = "Open your claimed territories",
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
                            contentDescription = "More map controls",
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
                .padding(bottom = panelHeight + 12.dp),
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
                    .padding(bottom = panelHeight + ORNAMENT_CLEARANCE),
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

        // The snackbar sits directly above the panel — anchored to the panel's
        // measured height so an "Undo" action is never covered.
        SnackbarHost(
            snackbarHost,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = panelHeight + 8.dp)
                .padding(horizontal = 12.dp),
        )

        // Capped and centred: a panel stretched across a landscape phone puts
        // the Start button an inch from the figures it belongs to, and on a
        // tablet it's a metre of empty card.
        ControlPanel(
            walk = walk,
            testMode = testMode,
            activityType = activityType,
            onSelectActivity = viewModel::setActivityType,
            hasLocationPermission = hasLocationPermission,
            permissionBlocked = permissionBlocked,
            onStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (testMode || hasLocationPermission) viewModel.startWalk() else onRequestPermission()
            },
            onClaim = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.stopWalk()
            },
            onFinishWithoutClaim = { confirmDiscardWalk = true },
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
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
            title = "Walk discarded",
            message = when (reason) {
                VoidReason.VEHICLE ->
                    "That looked like a vehicle trip, through all " +
                        "${MotionGate.MAX_STRIKES} warnings. Enclose only counts ground " +
                        "you cover walking, running or cycling, so this walk wasn't kept."
                VoidReason.TOO_FAST ->
                    "You were moving faster than a walk, run or ride through all " +
                        "${MotionGate.MAX_STRIKES} warnings, so this walk wasn't kept."
                VoidReason.UNVERIFIED_GAP ->
                    "Recording picked up a long way from where it stopped, so there's no " +
                        "record of how you covered the ground in between. This walk " +
                        "wasn't kept."
            },
            onDismiss = viewModel::dismissVoidedWalk,
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

    confirmSetHome?.let { here ->
        ConfirmDialog(
            title = "Set home here?",
            message = "Where you're standing now becomes your home. The home button " +
                "brings the map straight back to it; holding the button for three " +
                "seconds clears it again.",
            confirmLabel = "Set home",
            onConfirm = {
                confirmSetHome = null
                viewModel.setHome(here)
                controller.flyTo(here)
                scope.launch { snackbarHost.showSnackbar("Home set") }
            },
            onDismiss = { confirmSetHome = null },
        )
    }

    if (confirmResetHome) {
        ConfirmDialog(
            title = "Reset home position?",
            message = "Your home is cleared. The button then asks you to set it again " +
                "from wherever you are.",
            confirmLabel = "Reset home",
            destructive = true,
            onConfirm = {
                confirmResetHome = false
                viewModel.clearHome()
                scope.launch { snackbarHost.showSnackbar("Home cleared") }
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
            title = "Split screen",
            message = if (splitRequestedFrom) {
                "This device won't let an app rebuild the split from inside it. " +
                    "Drag the divider to the top or bottom edge to end this split, " +
                    "then pair Enclose with the app you want."
            } else {
                "This device doesn't let an app put itself into split screen. " +
                    "Open Recents, press and hold the Enclose card, choose Split screen, " +
                    "then pick the app for the other half."
            } + " Enclose keeps recording either way.",
            onDismiss = { showSplitHelp = false },
        )
    }

    if (floatingRefused) {
        NoticeDialog(
            title = "Couldn't float the window",
            message = "The system turned the request down. Picture-in-picture can be " +
                "switched off per app — check Settings › Apps › Enclose › " +
                "Picture-in-picture and try again.",
            onDismiss = { floatingRefused = false },
        )
    }

    if (noFixForHome) {
        NoticeDialog(
            title = "No position yet",
            message = "There's no GPS fix yet, so there's nothing to save as home. " +
                "Wait for your position to show on the map and try again.",
            onDismiss = { noFixForHome = false },
        )
    }

    if (confirmDiscardWalk) {
        ConfirmDialog(
            title = "Discard this ${walk.activityType.noun}?",
            message = "You haven't closed a loop yet, so nothing will be claimed. " +
                "Your route so far will be lost.",
            confirmLabel = "Discard ${walk.activityType.noun}",
            destructive = true,
            onConfirm = {
                confirmDiscardWalk = false
                viewModel.cancelWalk()
            },
            onDismiss = { confirmDiscardWalk = false },
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
                controller.fitTo(territory.polygons.flatten().flatten().ifEmpty { territory.ring })
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
            .clickable(onClickLabel = "Open your profile", role = Role.Button, onClick = onClick),
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
            Text("Loading map…", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// --- Bottom control panel ----------------------------------------------------

@Composable
private fun ControlPanel(
    walk: TrackingManager.WalkState,
    testMode: Boolean,
    activityType: ActivityType,
    onSelectActivity: (ActivityType) -> Unit,
    hasLocationPermission: Boolean,
    permissionBlocked: Boolean,
    onStart: () -> Unit,
    onClaim: () -> Unit,
    onFinishWithoutClaim: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onHowItWorks: () -> Unit,
    /** Minimised to a single row, so the map isn't half-covered. */
    collapsed: Boolean,
    /** False in a window too short for the expanded panel to be an option. */
    foldable: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val summary = PanelSummary.of(walk, testMode, hasLocationPermission, permissionBlocked)
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
                        onClickLabel = "Minimise the panel",
                        role = Role.Button,
                        onClick = { onCollapsedChange(true) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Minimise the panel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            when (summary.status) {
                PanelStatus.TRACKING, PanelStatus.BLOCKED, PanelStatus.READY -> {
                    LiveStats(walk, testMode = testMode)
                    WalkActions(
                        walk = walk,
                        onClaim = onClaim,
                        onFinishWithoutClaim = onFinishWithoutClaim,
                    )
                }

                // Location is the whole point of the app, so an explicit, actionable
                // recovery path replaces the old one-line red warning.
                PanelStatus.NO_PERMISSION -> PermissionBlock(
                    blocked = permissionBlocked,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )

                PanelStatus.IDLE -> {
                    IdleBlock(onHowItWorks = onHowItWorks)
                    ActivitySelector(selected = activityType, onSelect = onSelectActivity)
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = PillShape,
                    ) {
                        ButtonContent(Icons.Filled.PlayArrow, "Start ${activityType.noun}")
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
    /** Null where the window is too short for the panel to expand into. */
    onExpand: (() -> Unit)?,
) {
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
        PanelStatus.BLOCKED, PanelStatus.NO_PERMISSION -> MaterialTheme.colorScheme.error
        PanelStatus.READY -> accents.success
        PanelStatus.TRACKING -> accents.trail
        PanelStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (summary.status) {
        PanelStatus.IDLE -> "Ready to claim ground"
        PanelStatus.NO_PERMISSION -> "Location access needed"
        PanelStatus.BLOCKED -> "Paused — not recording"
        PanelStatus.READY -> "Back at the start"
        PanelStatus.TRACKING -> walk.activityType.activeLabel
    }
    val detail = when (summary.status) {
        PanelStatus.IDLE -> activityType.label
        PanelStatus.NO_PERMISSION -> null
        else -> "${formatDistance(walk.distanceMeters)} · ${formatElapsed(elapsedMs)}"
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
        )
        if (onExpand != null) {
            IconButton(onClick = onExpand, modifier = Modifier.size(TOUCH_TARGET)) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Expand the panel",
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
) {
    val label = when (summary.action) {
        PanelAction.START -> activityType.label
        PanelAction.CLAIM -> "Claim"
        PanelAction.END -> "End"
        PanelAction.GRANT_PERMISSION -> "Grant"
        PanelAction.OPEN_SETTINGS -> "Settings"
    }
    val onClick = when (summary.action) {
        PanelAction.START -> onStart
        PanelAction.CLAIM -> onClaim
        PanelAction.END -> onFinishWithoutClaim
        PanelAction.GRANT_PERMISSION -> onRequestPermission
        PanelAction.OPEN_SETTINGS -> onOpenAppSettings
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
        PanelAction.GRANT_PERMISSION, PanelAction.OPEN_SETTINGS -> Icons.Filled.LocationOff
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
                label = { Text(type.label) },
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
                "Ready to claim ground",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Walk a loop and finish near where you started to claim " +
                    "everything inside it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onHowItWorks, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "How Enclose works")
        }
    }
}

@Composable
private fun PermissionBlock(
    blocked: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
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
            Text("Location access needed", style = MaterialTheme.typography.titleMedium)
            Text(
                if (blocked) {
                    "Enable it in system settings to record walks."
                } else {
                    "Enclose traces your route to work out what you enclosed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(
        onClick = if (blocked) onOpenAppSettings else onRequestPermission,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = PillShape,
    ) {
        Text(if (blocked) "Open settings" else "Grant location access")
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
            ButtonContent(Icons.Filled.Flag, "Close loop & claim")
        }
        TextButton(onClick = onFinishWithoutClaim, modifier = Modifier.fillMaxWidth()) {
            Text("Discard ${walk.activityType.noun}")
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
            ButtonContent(Icons.Filled.Stop, "End ${walk.activityType.noun}")
        }
    }
}

@Composable
private fun LiveStats(walk: TrackingManager.WalkState, testMode: Boolean) {
    // Tick once a second so elapsed time and pace advance live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(walk.isTracking) {
        while (walk.isTracking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = walk.startedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
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
                if (blocked) "Paused" else walk.activityType.activeLabel,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // No GPS is involved in test mode, so don't report on it.
        if (!testMode) GpsAccuracyIndicator(walk.accuracyMeters)
    }

    // Five figures over two rows rather than one: at 20sp a fifth column leaves
    // about 68dp, which clips values like "8:20 /km" instead of ellipsing them.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Equal weights: the figures keep their columns as digits change.
        Metric(
            label = "Distance",
            value = formatDistance(walk.distanceMeters),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Time",
            value = formatElapsed(elapsedMs),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "Pace",
            value = formatPace(walk.distanceMeters, elapsedMs),
            modifier = Modifier.weight(1f),
        )
    }
    // A sibling row, not a nested one: the panel's Column spaces its children.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Metric(
            label = "Climb",
            value = formatClimb(walk.elevationGainMeters),
            modifier = Modifier.weight(1f),
        )
        Metric(
            label = "From start",
            value = walk.distanceToStartMeters?.let { formatDistance(it) } ?: EM_DASH,
            modifier = Modifier.weight(1f),
        )
        // Holds the third column so the two rows line up as a grid instead of
        // the lower pair drifting between the columns above it.
        Spacer(Modifier.weight(1f))
    }

    // While movement is rejected, the loop checklist is meaningless — what the
    // user needs is why nothing is being recorded and how long they have.
    if (blocked) MotionBlockedNotice(walk) else LoopProgress(walk)

    // A strike the walk survived. Kept on screen afterwards because the count is
    // what decides the next one: someone who doesn't know they're on their last
    // warning can't act on it.
    if (!blocked && walk.strikes > 0) StrikeNotice(walk)

    // A gap is not a failure and doesn't stop the walk, so it sits below the
    // checklist as a footnote rather than replacing it.
    if (walk.hadSignalGap && !testMode) SignalGapNotice()
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
                "GPS dropped out for a while — still recording, but part of your " +
                    "route is estimated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        is GpxImport.Reading -> GpxProgressDialog(label = "Reading the file…", progress = null)
        is GpxImport.Replaying -> GpxProgressDialog(
            label = "Replaying the track — ${state.done} of ${state.total} points",
            progress = if (state.total == 0) null else state.done.toFloat() / state.total,
        )

        is GpxImport.Done -> NoticeDialog(
            title = "Track imported",
            message = "${state.headline}\n\n${state.detail}",
            onDismiss = onDismiss,
        )

        is GpxImport.Failed -> NoticeDialog(
            title = "Couldn't import that",
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
internal fun GpxProgressDialog(label: String, progress: Float?) {
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
                Text("Importing GPX", style = MaterialTheme.typography.titleMedium)
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
                    if (vehicle) {
                        "Vehicle movement detected"
                    } else {
                        "Too fast for a ${walk.activityType.noun}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Not recording — Enclose only counts walking, running and cycling. " +
                    "Slow down to carry on with this ${walk.activityType.noun}.",
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
                    "Last warning — discarding this ${walk.activityType.noun} in " +
                        "${remainingMs / 1000}s"
                } else {
                    "Warning $pendingStrike of ${MotionGate.MAX_STRIKES} in " +
                        "${remainingMs / 1000}s"
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
                "${walk.strikes} of ${MotionGate.MAX_STRIKES} warnings used — " +
                    if (walk.strikesRemaining == 1) {
                        "one more ends this ${walk.activityType.noun}."
                    } else {
                        "${walk.strikesRemaining} left before this " +
                            "${walk.activityType.noun} is discarded."
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
            label = "Leave start",
            done = walk.hasLeftStart,
            progress = (toStart / leaveRadius).toFloat(),
        ),
        LoopStep(
            label = "Cover ${minPerimeter.roundToInt()} m",
            done = walk.distanceMeters >= minPerimeter,
            progress = (walk.distanceMeters / minPerimeter).toFloat(),
        ),
        LoopStep(
            label = "Return",
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
private fun loopHint(walk: TrackingManager.WalkState): String {
    val remaining = (TrackingManager.minPerimeterMeters - walk.distanceMeters).roundToInt()
    return when {
        walk.readyToClose ->
            "You're back at the start — close the loop to claim it!"
        walk.canCloseLoop ->
            "Head back to the start zone — ${walk.distanceToStartMeters?.roundToInt() ?: 0} m away"
        !walk.hasLeftStart ->
            "Move at least ${TrackingManager.leaveStartRadiusMeters.roundToInt()} m from your " +
                "start to begin the loop"
        else ->
            "Keep going — about $remaining m more before you can close"
    }
}

/** Colored dot + "±Xm" summarizing the current GPS fix quality. */
@Composable
private fun GpsAccuracyIndicator(accuracyMeters: Float?) {
    val accents = LocalEncloseAccents.current
    val (dot, label) = when {
        accuracyMeters == null -> MaterialTheme.colorScheme.onSurfaceVariant to "acquiring…"
        accuracyMeters <= 10f -> accents.gpsGood to "±${accuracyMeters.roundToInt()} m"
        accuracyMeters <= 25f -> accents.gpsFair to "±${accuracyMeters.roundToInt()} m"
        else -> accents.gpsPoor to "±${accuracyMeters.roundToInt()} m"
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
            "GPS $label",
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
                Text("Loop closed!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Name it and claim it as your own.",
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
                        label = "Area",
                        value = formatArea(pending.areaSqMeters),
                        modifier = Modifier.weight(1f),
                        accent = accent,
                    )
                    StatTile(
                        label = "Perimeter",
                        value = formatDistance(pending.perimeterMeters),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Climb rides on the caption rather than becoming a third tile,
                // for the width reason above.
                Text(
                    "Closed ${formatDistance(pending.distanceToStartMeters)} from your " +
                        "start · ${formatClimb(pending.elevationGainMeters)} climbed",
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
                        "Note: GPS dropped out along the way, so part of this outline is " +
                            "a straight line between the last fix before the gap and the " +
                            "first one after it.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Territory name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { name = NameGenerator.random() }) {
                            Icon(Icons.Filled.Casino, contentDescription = "Suggest another name")
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
                    "Color",
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
                        Text("Discard")
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
                        ButtonContent(Icons.Filled.Flag, "Claim")
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
            Text("How Enclose works", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Claim real ground by walking around it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            HowStep(
                number = "1",
                icon = Icons.Filled.PlayArrow,
                title = "Start a walk",
                body = "Your route is traced live on the map, and keeps recording " +
                    "while your screen is off.",
            )
            HowStep(
                number = "2",
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = "Walk a loop",
                body = "Head at least ${TrackingManager.leaveStartRadiusMeters.roundToInt()} m " +
                    "away and cover ${TrackingManager.minPerimeterMeters.roundToInt()} m or more, " +
                    "then curve back around.",
            )
            HowStep(
                number = "3",
                icon = Icons.Filled.Flag,
                title = "Close it and claim",
                body = "Step back into the dashed circle around your start and close the " +
                    "loop. Everything inside becomes yours — overlapping older claims " +
                    "get carved back.",
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
                        "Under your own power only. Enclose watches how you're moving — " +
                            "vehicle trips aren't recorded, and driving a loop won't claim it.",
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
                Text("Got it")
            }
        }
    }
}

@Composable
private fun HowStep(
    number: String,
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
                "$number · $title",
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
 * How wide the bottom panel is allowed to get. Beyond roughly this, the figures
 * at one end and the button at the other stop reading as one control — which is
 * what a landscape phone and any tablet would otherwise produce.
 */
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
