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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
    val basemapStyle by viewModel.basemapStyle.collectAsStateWithLifecycle()
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
    // Measured height of the bottom panel, so floating UI can clear it.
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val panelHeight = with(density) { panelHeightPx.toDp() }

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

        // --- Top row: claims count, test marker, menu, profile ----------------
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // Measured before the insets/padding modifiers so the reported
                // height covers everything the map must stay clear of.
                .onSizeChanged { topBarHeightPx = it.height }
                .statusBarsPadding()
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
                // Test mode is easy to leave on by accident, so it stays visible.
                AnimatedVisibility(visible = testMode, enter = fadeIn(), exit = fadeOut()) {
                    MapChip(
                        icon = Icons.Filled.TouchApp,
                        text = "Test",
                        contentDescription = "Turn off test mode",
                        onClick = { viewModel.setTestMode(false) },
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Box {
                    MapControlButton(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        onClick = { showMenu = true },
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (testMode) "Test mode · on" else "Test mode") },
                            onClick = {
                                viewModel.setTestMode(!testMode)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Filled.TouchApp, null) },
                            trailingIcon = {
                                if (testMode) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("How Enclose works") },
                            onClick = {
                                viewModel.openHowItWorks()
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
                        )
                    }
                }

                ProfileAvatarButton(
                    initials = profile.profile?.initials ?: "?",
                    onClick = onOpenProfile,
                )
            }
        }

        // --- Right rail: zoom + recenter, sitting above the panel -------------
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp)
                .padding(bottom = panelHeight + 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapControlButton(
                icon = Icons.Filled.Add,
                contentDescription = "Zoom in",
                enabled = controller.isStyleLoaded,
                onClick = { controller.zoomBy(ZOOM_BUTTON_STEP) },
            )
            MapControlButton(
                icon = Icons.Filled.Remove,
                contentDescription = "Zoom out",
                enabled = controller.isStyleLoaded,
                onClick = { controller.zoomBy(-ZOOM_BUTTON_STEP) },
            )
            MapControlButton(
                icon = Icons.Filled.MyLocation,
                contentDescription = "Recenter on my location",
                enabled = controller.canLocate,
                tint = MaterialTheme.colorScheme.primary,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    controller.recenter()
                },
            )
            // Basemap toggle: the dark map is hard to read in bright sun. Shows
            // the map you'd get by tapping, not the one you're looking at.
            MapControlButton(
                icon = if (basemapDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                contentDescription = if (basemapDark) {
                    "Switch to light map"
                } else {
                    "Switch to dark map"
                },
                enabled = controller.isStyleLoaded,
                onClick = {
                    viewModel.setBasemapStyle(
                        if (basemapDark) BasemapStyle.LIGHT else BasemapStyle.DARK,
                    )
                },
            )
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
            onEnableTestMode = { viewModel.setTestMode(true) },
            onHowItWorks = viewModel::openHowItWorks,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Measured outside the insets/margins so panelHeight is the full
                // space the panel occupies at the bottom of the screen.
                .onSizeChanged { panelHeightPx = it.height }
                .navigationBarsPadding()
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
                    "That looked like a vehicle trip. Enclose only counts ground you " +
                        "cover walking, running or cycling, so this walk wasn't kept."
                VoidReason.TOO_FAST ->
                    "You were moving faster than a walk, run or ride for too long, so " +
                        "this walk wasn't kept."
                VoidReason.UNVERIFIED_GAP ->
                    "Recording picked up a long way from where it stopped, so there's no " +
                        "record of how you covered the ground in between. This walk " +
                        "wasn't kept."
            },
            onDismiss = viewModel::dismissVoidedWalk,
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
    onEnableTestMode: () -> Unit,
    onHowItWorks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
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
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                // A walk in progress always owns the panel — losing permission
                // mid-walk must not hide the controls for the walk you're on.
                walk.isTracking -> {
                    LiveStats(walk, testMode = testMode)
                    WalkActions(
                        walk = walk,
                        onClaim = onClaim,
                        onFinishWithoutClaim = onFinishWithoutClaim,
                    )
                }

                // Location is the whole point of the app, so an explicit, actionable
                // recovery path replaces the old one-line red warning.
                !hasLocationPermission && !testMode -> PermissionBlock(
                    blocked = permissionBlocked,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onEnableTestMode = onEnableTestMode,
                )

                else -> {
                    IdleBlock(testMode = testMode, onHowItWorks = onHowItWorks)
                    ActivitySelector(selected = activityType, onSelect = onSelectActivity)
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = PillShape,
                    ) {
                        ButtonContent(
                            Icons.Filled.PlayArrow,
                            if (testMode) "Start test walk" else "Start ${activityType.noun}",
                        )
                    }
                }
            }
        }
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
private fun IdleBlock(testMode: Boolean, onHowItWorks: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (testMode) "Test mode" else "Ready to claim ground",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (testMode) {
                    "Tap the map to drop points, circle back near the first one, " +
                        "then close the loop."
                } else {
                    "Walk a loop and finish near where you started to claim " +
                        "everything inside it."
                },
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
    onEnableTestMode: () -> Unit,
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
    TextButton(onClick = onEnableTestMode, modifier = Modifier.fillMaxWidth()) {
        Text("Try it without GPS (test mode)")
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
                when {
                    blocked -> "Paused"
                    testMode -> "Tapping a loop"
                    else -> walk.activityType.activeLabel
                },
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
                "Discarding this walk in ${(remainingMs / 1000)}s",
                style = MaterialTheme.typography.labelMedium,
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
private fun HowItWorksSheet(onDismiss: () -> Unit) {
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

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "No GPS right now? Turn on test mode from the ⋮ menu and tap the map " +
                            "to build a loop by hand.",
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
