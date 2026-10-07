package io.app.enclose.ui

import io.app.enclose.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.annotation.StringRes
import io.app.enclose.BuildConfig
import io.app.enclose.export.GeoExporter
import io.app.enclose.data.VoidedWalk
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import java.io.File
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.Context
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
import androidx.compose.ui.text.style.TextAlign
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
    val res = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val testMode by encloseViewModel.testMode.collectAsStateWithLifecycle()
    val snapToPaths by encloseViewModel.snapToPaths.collectAsStateWithLifecycle()
    val snapBacklog by encloseViewModel.snapBacklog.collectAsStateWithLifecycle()
    val snappingExisting by encloseViewModel.snappingExisting.collectAsStateWithLifecycle()
    val gpxImport by encloseViewModel.gpxImport.collectAsStateWithLifecycle()
    val backupJob by encloseViewModel.backup.collectAsStateWithLifecycle()
    val showHowItWorks by encloseViewModel.showHowItWorks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    // Held by id, so the dialog names the walk the user actually tapped.
    var confirmDeleteVoided by remember { mutableStateOf<VoidedWalk?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
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
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profile_back))
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
                ButtonContent(Icons.AutoMirrored.Filled.Login, stringResource(R.string.profile_sign_in_soon))
            }

            val stats = state.stats

            SectionCard(title = stringResource(R.string.profile_section_conquest)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = stringResource(R.string.profile_stat_territories),
                        value = stats.territoryCount.toString(),
                        icon = Icons.Filled.Flag,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.profile_stat_area),
                        value = res.formatArea(stats.totalAreaSqMeters),
                        icon = Icons.Filled.CropSquare,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = stringResource(R.string.profile_stat_distance),
                        value = res.formatDistance(stats.totalDistanceMeters),
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.profile_stat_loops),
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
                    label = stringResource(R.string.profile_stat_climb),
                    value = res.formatClimb(stats.totalElevationGainMeters),
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
                SectionCard(title = stringResource(R.string.passport_title)) {
                    Text(
                        pluralStringResource(
                            R.plurals.passport_countries,
                            stats.stamps.size,
                            stats.stamps.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    stats.stamps.forEach { stamp -> CountryStampRow(stamp) }
                }
            }

            // Only worth a section once something has actually fallen.
            if (state.fallen.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.fallen_title)) {
                    Text(
                        stringResource(R.string.fallen_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    state.fallen.forEach { fallen -> FallenClaimRow(fallen) }
                }
            }

            // Only once there is one: a section explaining voids to someone who
            // has never had one is noise.
            if (state.voided.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.voided_title)) {
                    Text(
                        stringResource(R.string.voided_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    state.voided.forEach { walk ->
                        VoidedWalkRow(
                            walk = walk,
                            onShare = { shareError = shareVoidedWalk(context, walk) },
                            onDelete = { confirmDeleteVoided = walk },
                        )
                    }
                    shareError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.highlights_title)) {
                if (stats.territoryCount == 0) {
                    EmptyState(
                        icon = Icons.Filled.EmojiEvents,
                        title = stringResource(R.string.highlights_empty_title),
                        message = stringResource(R.string.highlights_empty_body),
                    )
                } else {
                    DetailRow(
                        stringResource(R.string.highlights_biggest_territory),
                        stats.biggestTerritoryName
                            ?.let {
                                stringResource(
                                    R.string.highlights_biggest_territory_value,
                                    it,
                                    res.formatArea(stats.biggestTerritoryAreaSqMeters),
                                )
                            }
                            ?: EM_DASH,
                    )
                    DetailRow(
                        stringResource(R.string.highlights_longest_walk),
                        if (stats.longestWalkMeters > 0) {
                            res.formatDistance(stats.longestWalkMeters)
                        } else {
                            EM_DASH
                        },
                    )
                    // An em dash, not "0 m": walks recorded before altitude was
                    // kept have no climb to report, which isn't a flat walk.
                    DetailRow(
                        stringResource(R.string.highlights_biggest_climb),
                        if (stats.biggestClimbMeters > 0) {
                            res.formatClimb(stats.biggestClimbMeters)
                        } else {
                            EM_DASH
                        },
                    )
                    DetailRow(
                        stringResource(R.string.highlights_first_claim),
                        stats.firstClaimEpochMs?.let { formatDay(it) } ?: EM_DASH,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.app_section_title)) {
                // The explainer lives here rather than in a map menu: it's read
                // once, and the map's own chrome is for things you reach for
                // mid-walk.
                DetailAction(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = stringResource(R.string.app_how_it_works_title),
                    subtitle = stringResource(R.string.app_how_it_works_subtitle),
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
                            Text(stringResource(R.string.app_test_mode_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.app_test_mode_body),
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
                            Text(stringResource(R.string.app_snap_title), style = MaterialTheme.typography.bodyLarge)
                            // Says plainly that something leaves the device. This
                            // is the only feature that does, and burying that
                            // would be the one thing not to do with it.
                            Text(
                                stringResource(R.string.app_snap_body),
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
                                stringResource(R.string.app_snap_existing_running)
                            } else {
                                stringResource(R.string.app_snap_existing)
                            },
                            subtitle = if (snappingExisting) {
                                stringResource(R.string.app_snap_existing_running_body)
                            } else {
                                val count = snapBacklog ?: 0
                                pluralStringResource(R.plurals.app_snap_existing_body, count, count)
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
                    title = stringResource(R.string.app_import_gpx_title),
                    subtitle = stringResource(R.string.app_import_gpx_body),
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
                    title = stringResource(R.string.app_backup_title),
                    // Says what is in it, because the user is about to put it
                    // somewhere: this file is a record of where they walk.
                    subtitle = stringResource(R.string.app_backup_body),
                    onClick = {
                        backupWriter.launch(encloseViewModel.suggestedBackupFileName())
                    },
                )

                DetailAction(
                    icon = Icons.Filled.Restore,
                    title = stringResource(R.string.app_restore_title),
                    // The promise that makes this safe to press is the one worth
                    // making on the button itself — see BackupRepository.
                    subtitle = stringResource(R.string.app_restore_body),
                    // Same wide filter as the GPX picker: providers hand JSON over
                    // as octet-stream at least as often as by its real type, and a
                    // correct filter would hide the file the user came to find.
                    onClick = { backupReader.launch(arrayOf("*/*")) },
                )
            }

            // versionCode is the build number: CI bumps it on every release
            // (see .github/workflows/ci.yml), so it identifies the build exactly.
            Text(
                text = stringResource(
                    if (BuildConfig.DEBUG) R.string.app_version_debug else R.string.app_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )

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
    confirmDeleteVoided?.let { walk ->
        AlertDialog(
            onDismissRequest = { confirmDeleteVoided = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.voided_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.voided_delete_body,
                        res.formatDistance(walk.distanceMeters),
                        res.formatRelativeDay(walk.startedAtEpochMs ?: walk.voidedAtEpochMs),
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteVoided(walk.id)
                    confirmDeleteVoided = null
                }) { Text(stringResource(R.string.voided_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVoided = null }) { Text(stringResource(R.string.voided_delete_keep)) } },
        )
    }

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
                        profile?.displayName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.profile_default_name),
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
                            stringResource(
                                if (profile?.isGuest != false) {
                                    R.string.profile_guest_offline
                                } else {
                                    R.string.profile_signed_in
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(TOUCH_TARGET)) {
                    Icon(Icons.Filled.Casino, contentDescription = stringResource(R.string.profile_roll_name))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(TOUCH_TARGET)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_edit_name))
                }
            }
        }
    }
}

/** One country stamp: when it was first walked, and which cities in it. */
@Composable
private fun CountryStampRow(stamp: CountryStamp) {
    val res = LocalResources.current
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
                    pluralStringResource(
                        R.plurals.passport_country_detail,
                        stamp.territoryCount,
                        stamp.territoryCount,
                        formatDay(stamp.firstClaimedAtEpochMs),
                    )
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
            res.formatArea(stamp.claimedAreaSqMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One voided walk: when, how far, why it didn't count, and what can be done with it. */
@Composable
private fun VoidedWalkRow(walk: VoidedWalk, onShare: () -> Unit, onDelete: () -> Unit) {
    val res = LocalResources.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.DirectionsWalk,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.voided_row,
                    res.formatDistance(walk.distanceMeters),
                    res.formatRelativeDay(walk.startedAtEpochMs ?: walk.voidedAtEpochMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(voidedReasonLabel(walk.reason)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.voided_share))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.voided_delete))
        }
    }
}

@StringRes
private fun voidedReasonLabel(reason: VoidedWalk.Reason): Int = when (reason) {
    VoidedWalk.Reason.VEHICLE -> R.string.voided_reason_vehicle
    VoidedWalk.Reason.TOO_FAST -> R.string.voided_reason_too_fast
    VoidedWalk.Reason.UNVERIFIED_GAP -> R.string.voided_reason_gap
}

/**
 * Write the walk as GPX to cacheDir and open the share sheet — the same route
 * the territory screen's export takes. Returns a message on failure rather than
 * throwing: a full cache must not take the screen down.
 */
private fun shareVoidedWalk(context: Context, walk: VoidedWalk): String? = runCatching {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "${GeoExporter.safeFileName(walk)}.gpx")
    file.writeText(GeoExporter.toGpx(walk))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.voided_share_chooser)).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )
    null
}.getOrElse { context.getString(R.string.voided_share_failed) }

/** One absorbed territory: what it was, how big, and what took it. */
@Composable
private fun FallenClaimRow(fallen: FallenClaim) {
    val res = LocalResources.current
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
                    ?.let {
                        stringResource(
                            R.string.fallen_absorbed_by,
                            it,
                            res.formatRelativeDay(fallen.conqueredAtEpochMs),
                        )
                    }
                    ?: stringResource(
                        R.string.fallen_absorbed,
                        res.formatRelativeDay(fallen.conqueredAtEpochMs),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            res.formatArea(fallen.areaSqMeters),
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
                onClickLabel = stringResource(R.string.coverage_open_cities),
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
                            top?.label() ?: stringResource(R.string.coverage_region_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (top != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.coverage_filled_in),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                .copy(alpha = 0.75f),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.coverage_percent, percent.roundToInt()),
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
                stringResource(
                    if (top == null) R.string.coverage_explain_empty else R.string.coverage_explain,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            if (hasCities) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (namedCityCount > 1) {
                            pluralStringResource(
                                R.plurals.coverage_see_all_cities,
                                namedCityCount,
                                namedCityCount,
                            )
                        } else {
                            stringResource(R.string.coverage_see_breakdown)
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
            Text(stringResource(R.string.coverage_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.coverage_sheet_subtitle),
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
                    stringResource(R.string.coverage_sheet_unplaced_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The name a city is shown under. Claims with no city yet are grouped under one
 * label, which lives here rather than in [CityCoverage] so that pure, tested
 * class stays free of text.
 */
@Composable
private fun CityCoverage.label(): String =
    if (isUnknown) stringResource(R.string.coverage_unplaced) else city

@Composable
private fun CityCoverageRow(city: CityCoverage) {
    val res = LocalResources.current
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
                    city.label(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.coverage_percent, city.percent.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        ProgressTrack(progress = (city.percent / 100.0).toFloat(), color = accent)
        Spacer(Modifier.height(6.dp))
        Text(
            pluralStringResource(
                R.plurals.coverage_city_detail,
                city.territoryCount,
                city.territoryCount,
                res.formatArea(city.claimedAreaSqMeters),
            ),
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
        title = { Text(stringResource(R.string.edit_name_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.edit_name_first)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = last,
                    onValueChange = { last = it },
                    label = { Text(stringResource(R.string.edit_name_last)) },
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
            ) { Text(stringResource(R.string.edit_name_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_name_cancel)) } },
    )
}
