package io.app.enclose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.data.CityCoverage
import io.app.enclose.export.Backup
import io.app.enclose.data.CountryStamp
import io.app.enclose.data.Profile
import io.app.enclose.ui.theme.PillShape
import kotlin.math.roundToInt

/**
 * Offline profile and lifetime stats. Everything here is derived locally, so it
 * works with no account and no network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
    /**
     * The same activity-scoped instance the map screen uses — the app's own
     * settings (the explainer, test mode, GPX import) live here now, and they
     * are state about the walk, not about the profile.
     */
    encloseViewModel: EncloseViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val testMode by encloseViewModel.testMode.collectAsStateWithLifecycle()
    val snapToPaths by encloseViewModel.snapToPaths.collectAsStateWithLifecycle()
    val snapBacklog by encloseViewModel.snapBacklog.collectAsStateWithLifecycle()
    val snappingExisting by encloseViewModel.snappingExisting.collectAsStateWithLifecycle()
    val gpxImport by encloseViewModel.gpxImport.collectAsStateWithLifecycle()
    val backupJob by encloseViewModel.backup.collectAsStateWithLifecycle()
    val showHowItWorks by encloseViewModel.showHowItWorks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var showCities by rememberSaveable { mutableStateOf(false) }

    // Recount on every visit: claims are made elsewhere, so a count taken once
    // would go stale the moment someone walks a loop and comes back here.
    androidx.compose.runtime.LaunchedEffect(Unit) { encloseViewModel.refreshSnapBacklog() }

    // OpenDocument rather than GetContent: it gives a durable, readable uri, and
    // the picker it opens is the one people expect for "find my file".
    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(encloseViewModel::importGpx) }

    // The backup goes wherever the user says — a cloud folder, an SD card, a
    // cable's reach away — rather than into the app's own storage, which is the
    // one place a backup is no use: uninstalling takes it with it.
    val backupWriter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Backup.MIME_TYPE),
    ) { uri -> uri?.let(encloseViewModel::exportBackup) }
    val backupReader = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(encloseViewModel::importBackup) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        // safeDrawing, not the default systemBars: in landscape the display
        // cutout is on the side, where the bar insets don't reach.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileHeader(
                profile = state.profile,
                onEdit = { editing = true },
                onRegenerate = viewModel::regenerateName,
            )

            // Sign-in is future work — visible but disabled so users know it's coming.
            OutlinedButton(
                onClick = {},
                enabled = false,
                shape = PillShape,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                ButtonContent(Icons.AutoMirrored.Filled.Login, "Sign in (coming soon)")
            }

            val stats = state.stats

            SectionCard(title = "Your conquest") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = "Territories",
                        value = stats.territoryCount.toString(),
                        icon = Icons.Filled.Flag,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Area conquered",
                        value = formatArea(stats.totalAreaSqMeters),
                        icon = Icons.Filled.CropSquare,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = "Distance walked",
                        value = formatDistance(stats.totalDistanceMeters),
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Loops closed",
                        value = stats.walkCount.toString(),
                        icon = Icons.Filled.Loop,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Full width rather than paired with an invented sixth figure —
                // three columns would ellipsize values like "1.24 km²".
                StatTile(
                    label = "Elevation climbed",
                    value = formatClimb(stats.totalElevationGainMeters),
                    icon = Icons.Filled.Terrain,
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CoverageCard(
                cities = stats.cities,
                top = stats.topCity,
                onOpenCities = { showCities = true },
            )

            // Countries only become interesting once one has been stamped, and
            // the section stays hidden until a lookup has actually resolved one.
            if (stats.stamps.isNotEmpty()) {
                SectionCard(title = "Passport") {
                    Text(
                        if (stats.stamps.size == 1) {
                            "One country walked."
                        } else {
                            "${stats.stamps.size} countries walked."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    stats.stamps.forEach { stamp -> CountryStampRow(stamp) }
                }
            }

            // Only worth a section once something has actually fallen.
            if (state.fallen.isNotEmpty()) {
                SectionCard(title = "Fallen claims") {
                    Text(
                        "Ground you took back with a later walk. These left the map " +
                            "but the walks that earned them are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    state.fallen.forEach { fallen -> FallenClaimRow(fallen) }
                }
            }

            SectionCard(title = "Highlights") {
                if (stats.territoryCount == 0) {
                    EmptyState(
                        icon = Icons.Filled.EmojiEvents,
                        title = "Nothing to show yet",
                        message = "Claim your first territory and your records will " +
                            "start filling in here.",
                    )
                } else {
                    DetailRow(
                        "Biggest territory",
                        stats.biggestTerritoryName
                            ?.let { "$it · ${formatArea(stats.biggestTerritoryAreaSqMeters)}" }
                            ?: EM_DASH,
                    )
                    DetailRow(
                        "Longest walk",
                        if (stats.longestWalkMeters > 0) {
                            formatDistance(stats.longestWalkMeters)
                        } else {
                            EM_DASH
                        },
                    )
                    // An em dash, not "0 m": walks recorded before altitude was
                    // kept have no climb to report, which isn't a flat walk.
                    DetailRow(
                        "Biggest climb",
                        if (stats.biggestClimbMeters > 0) {
                            formatClimb(stats.biggestClimbMeters)
                        } else {
                            EM_DASH
                        },
                    )
                    DetailRow(
                        "First claim",
                        stats.firstClaimEpochMs?.let { formatDay(it) } ?: EM_DASH,
                    )
                }
            }

            SectionCard(title = "App") {
                // The explainer lives here rather than in a map menu: it's read
                // once, and the map's own chrome is for things you reach for
                // mid-walk.
                DetailAction(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = "How Enclose works",
                    subtitle = "Walk a loop, come back, claim what's inside.",
                    onClick = encloseViewModel::openHowItWorks,
                )

                // Debug builds only. In a shipped build the switch would offer to
                // replace GPS with map taps, so a walk started after finding it
                // records nothing and the route is gone — see
                // EncloseViewModel.devToolsAvailable.
                if (encloseViewModel.devToolsAvailable) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Test mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Tap the map to drop points instead of walking. " +
                                    "Nothing is recorded from GPS while it's on.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = testMode, onCheckedChange = encloseViewModel::setTestMode)
                    }
                }

                // Hidden entirely where no matching service is bound: a switch
                // that cannot do anything is worse than no switch.
                if (encloseViewModel.snapAvailable) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Snap routes to paths", style = MaterialTheme.typography.bodyLarge)
                            // Says plainly that something leaves the device. This
                            // is the only feature that does, and burying that
                            // would be the one thing not to do with it.
                            Text(
                                "Draws claims along real roads instead of your raw GPS " +
                                    "trace. Sends the route of each new claim to a map " +
                                    "service to do it. Areas never change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = snapToPaths,
                            onCheckedChange = encloseViewModel::setSnapToPaths,
                        )
                    }

                    // Existing claims are never swept up by the switch itself, so
                    // this says how many walks it would send before it sends any.
                    if (snapToPaths && (snapBacklog ?: 0) > 0) {
                        Spacer(Modifier.height(4.dp))
                        DetailAction(
                            icon = Icons.Filled.Route,
                            title = if (snappingExisting) {
                                "Snapping existing claims…"
                            } else {
                                "Snap existing claims"
                            },
                            subtitle = if (snappingExisting) {
                                "Working through them now."
                            } else {
                                "Sends $snapBacklog earlier " +
                                    "${if (snapBacklog == 1) "walk" else "walks"} to the map " +
                                    "service. New claims are already covered."
                            },
                            onClick = {
                                if (!snappingExisting) encloseViewModel.snapExistingClaims()
                            },
                        )
                    }
                }

                // Not test-mode-only any more: sharing a track into Enclose from
                // another app imports it in every build, and a picker that only
                // exists in debug would make the same capability reachable by one
                // door and not the other. Refused while a GPS walk is running —
                // see EncloseViewModel.importGpx.
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                DetailAction(
                    icon = Icons.Filled.UploadFile,
                    title = "Import GPX…",
                    subtitle = "Replay a track recorded elsewhere, then close the loop " +
                        "to claim it. You can also share a GPX straight to Enclose.",
                    // Most providers hand GPX over as application/octet-stream or
                    // nothing at all, so a narrow filter mostly hides the file the
                    // user came to pick.
                    onClick = { gpxPicker.launch(arrayOf("*/*")) },
                )

                // Backup and restore sit together, in that order: the two are one
                // idea, and the one people come looking for first is the one they
                // need *before* anything has gone wrong.
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                DetailAction(
                    icon = Icons.Filled.Save,
                    title = "Back up everything…",
                    // Says what is in it, because the user is about to put it
                    // somewhere: this file is a record of where they walk.
                    subtitle = "Writes one file holding every claim, every walk, your " +
                        "profile and your settings. Keep it somewhere safe — anyone who " +
                        "opens it can read where you walk.",
                    onClick = {
                        backupWriter.launch(encloseViewModel.suggestedBackupFileName())
                    },
                )

                DetailAction(
                    icon = Icons.Filled.Restore,
                    title = "Restore from a backup…",
                    // The promise that makes this safe to press is the one worth
                    // making on the button itself — see BackupRepository.
                    subtitle = "Brings back everything in a backup file. Nothing already " +
                        "on this phone is deleted.",
                    // Same wide filter as the GPX picker: providers hand JSON over
                    // as octet-stream at least as often as by its real type, and a
                    // correct filter would hide the file the user came to find.
                    onClick = { backupReader.launch(arrayOf("*/*")) },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Both started from here, so both report back here — the import outlives
    // this screen, but the user is standing on it when they kick it off.
    GpxImportDialogs(gpxImport, onDismiss = encloseViewModel::dismissGpxImport)
    BackupDialogs(backupJob, onDismiss = encloseViewModel::dismissBackup)
    if (showHowItWorks) HowItWorksSheet(onDismiss = encloseViewModel::dismissHowItWorks)

    if (showCities && state.stats.cities.isNotEmpty()) {
        CityCoverageSheet(
            cities = state.stats.cities,
            onDismiss = { showCities = false },
        )
    }

    // Guarded on a loaded profile so the fields are never pre-filled from null.
    val loadedProfile = state.profile
    if (editing && loadedProfile != null) {
        EditNameDialog(
            profile = loadedProfile,
            onConfirm = { first, last ->
                viewModel.updateName(first, last)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun ProfileHeader(
    profile: Profile?,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                    ),
                ),
            ),
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InitialsAvatar(initials = profile?.initials ?: "?", size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.displayName?.ifBlank { "Walker" } ?: "Walker",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = PillShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            if (profile?.isGuest != false) "Guest · offline" else "Signed in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(TOUCH_TARGET)) {
                    Icon(Icons.Filled.Casino, contentDescription = "Roll a random name")
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(TOUCH_TARGET)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name")
                }
            }
        }
    }
}

/** One country stamp: when it was first walked, and which cities in it. */
@Composable
private fun CountryStampRow(stamp: CountryStamp) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stamp.country,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Cities are the interesting detail, but a claim can resolve a
                // country without a city, so fall back to the count and date.
                if (stamp.cities.isEmpty()) {
                    "${stamp.territoryCount} claims · since ${formatDay(stamp.firstClaimedAtEpochMs)}"
                } else {
                    stamp.cities.joinToString(", ")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatArea(stamp.claimedAreaSqMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One absorbed territory: what it was, how big, and what took it. */
@Composable
private fun FallenClaimRow(fallen: FallenClaim) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fallen.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The claim that took it may have been deleted since; say what
                // happened either way rather than showing a dangling name.
                fallen.takenByName
                    ?.let { "Absorbed by $it · ${formatRelativeDay(fallen.conqueredAtEpochMs)}" }
                    ?: "Absorbed ${formatRelativeDay(fallen.conqueredAtEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatArea(fallen.areaSqMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How densely the walker has filled in their strongest city, and the way into
 * the rest. The metric needs explaining, so the explanation sits with it rather
 * than in a tooltip.
 */
@Composable
private fun CoverageCard(
    cities: List<CityCoverage>,
    top: CityCoverage?,
    onOpenCities: () -> Unit,
) {
    val percent = top?.percent ?: 0.0
    val hasCities = cities.isNotEmpty()
    // Unplaced claims are a group, not a city, so they don't get counted as one.
    val namedCityCount = cities.count { !it.isUnknown }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = hasCities,
                onClickLabel = "See every city you've walked",
                role = Role.Button,
                onClick = onOpenCities,
            ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocationCity,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // No claims yet, or none placed: don't name a city
                            // we don't know — say what the number measures.
                            top?.displayName ?: "Region filled in",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (top != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Filled in",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                .copy(alpha = 0.75f),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${percent.roundToInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            ProgressTrack(
                progress = (percent / 100.0).toFloat(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                // "there" and "that city" only mean something once a city is
                // named; before the first claim they refer to nothing.
                if (top == null) {
                    "Once you claim your first loop, this shows how completely " +
                        "you've filled in the city you walk."
                } else {
                    "Your claimed area there as a share of the box that contains " +
                        "your claims in that city — how completely you've taken " +
                        "the ground you roam."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            if (hasCities) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (namedCityCount > 1) {
                            "See all $namedCityCount cities"
                        } else {
                            "See the breakdown"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Every city the walker has claimed in, strongest first.
 *
 * Each city is measured against its own bounding box rather than one box around
 * everything: two cities are mostly the countryside between them, which would
 * drag every percentage towards zero and make travelling look like a loss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CityCoverageSheet(
    cities: List<CityCoverage>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Cap the list instead of forcing the sheet tall, so two cities produce a
    // compact sheet and a well-travelled walker still scrolls.
    val listMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Where you've walked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "How much of each city you've filled in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Column(
                Modifier
                    .heightIn(max = listMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                cities.forEach { city -> CityCoverageRow(city) }
            }

            if (cities.any { it.isUnknown }) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Claims are placed when there's a connection. Anything walked " +
                        "offline is named the next time you open this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CityCoverageRow(city: CityCoverage) {
    val accent = if (city.isUnknown) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (city.isUnknown) Icons.Filled.TravelExplore else Icons.Filled.LocationCity,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    city.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${city.percent.roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressTrack(progress = (city.percent / 100.0).toFloat(), color = accent)
        Spacer(Modifier.height(6.dp))
        Text(
            "${city.territoryCount} ${if (city.territoryCount == 1) "claim" else "claims"} · " +
                formatArea(city.claimedAreaSqMeters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditNameDialog(
    profile: Profile,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf(profile.firstName) }
    var last by rememberSaveable { mutableStateOf(profile.lastName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Edit name") },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("First name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text("Last name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(first.trim(), last.trim()) },
                enabled = first.isNotBlank() || last.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
