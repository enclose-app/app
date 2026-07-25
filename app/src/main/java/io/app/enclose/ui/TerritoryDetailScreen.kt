package io.app.enclose.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.EncloseApp
import io.app.enclose.data.Territory
import io.app.enclose.data.Walk
import io.app.enclose.export.GeoExporter
import io.app.enclose.geo.Geo
import io.app.enclose.geo.Place
import io.app.enclose.ui.theme.PillShape
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full detail view for a single territory: a large shape preview, complete
 * stats, editable notes, recolor swatches, rename/delete/share actions and a
 * "show on map" shortcut. Resolves the territory by id from the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryDetailScreen(
    territoryId: String,
    onBack: () -> Unit,
    onShowOnMap: (Territory) -> Unit = {},
    onDelete: (Territory) -> Unit = {},
    viewModel: EncloseViewModel = viewModel(),
) {
    val territories by viewModel.territories.collectAsStateWithLifecycle()
    val walksById by viewModel.walksById.collectAsStateWithLifecycle()
    val territory = territories.firstOrNull { it.id == territoryId }

    // Leave only once we know the territory is really gone. The flow starts
    // empty, so bouncing on the first null would kick us out of the screen
    // before the database has answered (e.g. after process death).
    LaunchedEffect(territories) {
        if (territory == null && territories.isNotEmpty()) onBack()
    }
    if (territory == null) {
        LoadingScreen(onBack)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accent = hexColor(territory.colorHex)
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Notes are edited locally, then committed on Save.
    var notes by rememberSaveable(territoryId) { mutableStateOf(territory.notes) }
    val notesDirty = notes != territory.notes

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        territory.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Show on map") },
                                leadingIcon = { Icon(Icons.Filled.Map, null) },
                                onClick = {
                                    menuOpen = false
                                    onShowOnMap(territory)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmingDelete = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TerritoryHero(territory = territory, accent = accent)

            // Area and perimeter already lead the hero, so this card carries only
            // what the hero doesn't say.
            SectionCard(title = "Details") {
                DetailRow("Boundary points", territory.ring.size.toString())
                DetailRow("Claimed", formatDate(territory.claimedAtEpochMs))
                Spacer(Modifier.height(6.dp))
                SyncBadge(territory.syncStatus)
            }

            WalkCard(walksById[territory.id])

            LocationCard(territory)

            SectionCard(title = "Color") {
                ColorPickerRow(
                    selectedHex = territory.colorHex,
                    onSelect = { viewModel.recolorTerritory(territory.id, it) },
                )
            }

            SectionCard(title = "Notes") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp),
                    placeholder = { Text("What happened on this walk?") },
                    shape = MaterialTheme.shapes.small,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Only offer the actions when there is something to act on,
                    // rather than showing a permanently disabled "Saved" button.
                    if (notesDirty) {
                        TextButton(onClick = { notes = territory.notes }) { Text("Revert") }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                viewModel.updateNotes(territory.id, notes)
                                scope.launch { snackbarHost.showSnackbar("Notes saved") }
                            },
                        ) { Text("Save notes") }
                    } else {
                        Text(
                            if (notes.isBlank()) "No notes yet" else "Saved",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = { onShowOnMap(territory) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = PillShape,
            ) {
                ButtonContent(Icons.Filled.Map, "Show on map")
            }

            SectionCard(title = "Export & share") {
                Text(
                    "Send this claim to another app — GeoJSON for mapping tools, " +
                        "GPX for fitness apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExportFormat.entries.forEach { format ->
                        OutlinedButton(
                            onClick = {
                                val error = shareTerritory(context, territory, format)
                                if (error != null) {
                                    scope.launch { snackbarHost.showSnackbar(error) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = PillShape,
                        ) {
                            ButtonContent(Icons.Filled.Share, format.label)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (renaming) {
        TextEntryDialog(
            title = "Rename territory",
            label = "Name",
            initialValue = territory.name,
            onConfirm = { newName ->
                viewModel.renameTerritory(territory.id, newName)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingDelete) {
        ConfirmDialog(
            title = "Delete territory?",
            message = "“${territory.name}” will be removed. You can undo right after.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmingDelete = false
                onDelete(territory)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * Big shape preview on a wash of the territory's own color, with its headline
 * figures overlaid — the shape is the thing the user actually earned, so it
 * leads the screen.
 */
@Composable
private fun TerritoryHero(territory: Territory, accent: Color) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.04f)),
                ),
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f),
                    contentAlignment = Alignment.Center,
                ) {
                    PolygonThumbnail(territory, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Area",
                        value = formatArea(territory.areaSqMeters),
                        modifier = Modifier.weight(1f),
                        accent = accent,
                        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    )
                    StatTile(
                        label = "Perimeter",
                        value = formatDistance(territory.perimeterMeters),
                        modifier = Modifier.weight(1f),
                        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * Where this loop was walked: the coordinates it encloses, and the place names
 * for them when they can be had.
 *
 * Everything here is best effort and nothing blocks the screen. The coordinates
 * are computed locally and always shown. The city already stored on the claim
 * appears immediately, so an offline visit still says something; the live
 * lookup then fills in area and country when the geocoder can reach them.
 * Fields that don't resolve are left out rather than shown empty — the platform
 * geocoder routinely names a country but not a city, and a row reading "—"
 * would suggest the walk lacks something it doesn't.
 */
/**
 * How the ground was covered, as opposed to what was won. Every row is
 * conditional: walks recorded before these were captured have no start time and
 * no altitude, and an old claim showing "0 m climb" would be a lie rather than a
 * gap. The card disappears entirely when there is nothing true to say.
 */
@Composable
private fun WalkCard(walk: Walk?) {
    if (walk == null) return
    val duration = walk.durationMs
    val moving = walk.movingMs?.takeIf { it > 0 }
    val pacing = walk.pacingMs
    val hasClimb = walk.elevationGainMeters > 0.0
    if (duration == null && !hasClimb) return

    SectionCard(title = "The walk") {
        if (duration != null) {
            DetailRow("Duration", formatElapsed(duration))
            // Only worth its own row when it differs enough to notice; on a walk
            // with no stops it would just repeat the duration.
            if (moving != null && duration - moving >= NOTABLE_PAUSE_MS) {
                DetailRow("Moving", formatElapsed(moving))
            }
        }
        if (pacing != null) {
            DetailRow("Pace", formatPace(walk.perimeterMeters, pacing))
        }
        if (hasClimb) {
            DetailRow("Elevation gain", formatDistance(walk.elevationGainMeters))
        }
    }
}

@Composable
private fun LocationCard(territory: Territory) {
    val context = LocalContext.current
    val center = remember(territory.id) {
        territory.ring.takeIf { it.isNotEmpty() }?.let { Geo.centroid(it) }
    }

    // Seeded from the claim's stored city so there's something to read before
    // (and without) a network round trip.
    var place by remember(territory.id) {
        mutableStateOf(Place(city = territory.city.takeIf { it.isNotBlank() }))
    }
    var resolving by remember(territory.id) { mutableStateOf(center != null) }

    LaunchedEffect(territory.id) {
        val point = center ?: return@LaunchedEffect
        val resolved = (context.applicationContext as EncloseApp)
            .cityResolver
            .resolvePlace(point)
        // Keep the stored city if the lookup came back without one.
        if (resolved != null) place = resolved.copy(city = resolved.city ?: place.city)
        resolving = false
    }

    SectionCard(title = "Location") {
        if (center != null) {
            DetailRow("Coordinates", formatCoordinates(center))
        }
        place.city?.let { DetailRow("City", it) }
        place.area?.let { DetailRow("Area", it) }
        place.country?.let {
            DetailRow("Country", place.countryCode?.let { code -> "$it ($code)" } ?: it)
        }

        if (place.isEmpty) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (resolving) {
                    "Looking up the place names…"
                } else {
                    "Place names need a connection — they'll fill in next time " +
                        "you open this with one."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown while the database is still answering, instead of a blank bounce-back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private enum class ExportFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    GPX("GPX", "gpx", "application/gpx+xml"),
}

/**
 * Write the chosen export to cacheDir and fire the Android share sheet.
 * Returns null on success, or a message to show the user — a full cache or a
 * device with no app able to receive the file must not take the screen down.
 */
private fun shareTerritory(
    context: Context,
    territory: Territory,
    format: ExportFormat,
): String? = runCatching {
    val content = when (format) {
        ExportFormat.GEOJSON -> GeoExporter.toGeoJson(territory)
        ExportFormat.GPX -> GeoExporter.toGpx(territory)
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "${GeoExporter.safeFileName(territory)}.${format.extension}")
    file.writeText(content)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, territory.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share ${format.label}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
    null
}.getOrElse { "Couldn't share as ${format.label}" }

/**
 * Below this, the difference between elapsed and moving time is noise from a
 * couple of slow steps rather than a stop worth reporting.
 */
private const val NOTABLE_PAUSE_MS = 30_000L
