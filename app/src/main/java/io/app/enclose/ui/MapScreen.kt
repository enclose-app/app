package io.app.enclose.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.TrackingManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    viewModel: EncloseViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val walk by viewModel.walk.collectAsStateWithLifecycle()
    val territories by viewModel.territories.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val pendingClaim by viewModel.pendingClaim.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Incremented by the recenter button to signal EncloseMap to fly to the user.
    var recenterTick by remember { mutableStateOf(0) }
    // Territory list sheet + map focus.
    var showList by remember { mutableStateOf(false) }
    var focusTick by remember { mutableStateOf(0) }
    var focusPoints by remember { mutableStateOf<List<io.app.enclose.geo.LatLng>>(emptyList()) }

    // Celebrate each claimed loop.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.claimEvents.collect { t ->
            snackbarHost.showSnackbar("Claimed ${t.name} · ${formatArea(t.areaSqMeters)}")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            EncloseMap(
                walk = walk,
                territories = territories,
                hasLocationPermission = hasLocationPermission,
                onMapTap = if (testMode) viewModel::addTestPoint else null,
                recenterTrigger = recenterTick,
                focusTrigger = focusTick,
                focusPoints = focusPoints,
                modifier = Modifier.fillMaxSize(),
            )

            // Top-left: territory count — tap to open the list of claims.
            TerritoryBadge(
                count = territories.size,
                onClick = { showList = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(padding)
                    .padding(12.dp),
            )

            // Top-right: test-mode toggle (tap the map to drop points).
            TestToggle(
                enabled = testMode,
                onToggle = viewModel::setTestMode,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(padding)
                    .padding(12.dp),
            )

            // Bottom: controls + live stats.
            ControlPanel(
                walk = walk,
                testMode = testMode,
                hasLocationPermission = hasLocationPermission,
                onStart = {
                    if (testMode || hasLocationPermission) viewModel.startWalk() else onRequestPermission()
                },
                onStop = { viewModel.stopWalk() },
                onRecenter = { recenterTick++ },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(padding)
                    .padding(16.dp),
            )
        }

        pendingClaim?.let { pending ->
            ClaimDialog(
                pending = pending,
                onClaim = viewModel::confirmClaim,
                onDiscard = viewModel::discardClaim,
            )
        }

        if (showList) {
            TerritoryListSheet(
                territories = territories,
                onDismiss = { showList = false },
                onSelect = { territory ->
                    focusPoints = territory.ring
                    focusTick++
                    showList = false
                },
                onRename = viewModel::renameTerritory,
                onDelete = viewModel::deleteTerritory,
            )
        }
    }
}

/** Palette offered when claiming a territory. */
private val CLAIM_PALETTE = listOf(
    "#7B1FA2", // purple (brand)
    "#AB47BC", // orchid
    "#1E88E5", // blue
    "#F2A65A", // amber
    "#E53935", // red
    "#00897B", // teal
)

/** The flowing purple→amber gradient used to signal a ready / successful loop. */
@Composable
private fun rememberFlowingGradient(): Brush {
    val transition = rememberInfiniteTransition(label = "flowGrad")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    val span = 480f
    val x = shift * span
    return Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary,
        ),
        start = Offset(x - span, 0f),
        end = Offset(x, 0f),
        tileMode = TileMode.Mirror,
    )
}

@Composable
private fun ClaimDialog(
    pending: TrackingManager.PendingClaim,
    onClaim: (String, String) -> Unit,
    onDiscard: () -> Unit,
) {
    var name by remember { mutableStateOf(pending.suggestedName) }
    var colorHex by remember { mutableStateOf(CLAIM_PALETTE.first()) }
    val accent = hexColor(colorHex)
    val shape = RoundedCornerShape(28.dp)

    Dialog(onDismissRequest = onDiscard) {
        Surface(
            modifier = Modifier.border(BorderStroke(3.dp, rememberFlowingGradient()), shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
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
                Text(
                    "Loop closed!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Name it and claim it as your own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatTile(Modifier.weight(1f), "Area", formatArea(pending.areaSqMeters))
                    StatTile(Modifier.weight(1f), "Perimeter", formatDistance(pending.perimeterMeters))
                    StatTile(Modifier.weight(1f), "From start", formatDistance(pending.distanceToStartMeters))
                }

                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Territory name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        cursorColor = accent,
                        focusedBorderColor = accent,
                        focusedLabelColor = accent,
                    ),
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    "Color",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CLAIM_PALETTE.forEach { hex ->
                        ColorSwatch(hex, hex == colorHex) { colorHex = hex }
                    }
                }

                Spacer(Modifier.height(26.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDiscard,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Discard")
                    }
                    Button(
                        onClick = { onClaim(name, colorHex) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Claim")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(hexColor(hex))
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

@Composable
private fun TerritoryBadge(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
            Text(
                "$count claimed",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerritoryListSheet(
    territories: List<Territory>,
    onDismiss: () -> Unit,
    onSelect: (Territory) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf<Territory?>(null) }
    var deleting by remember { mutableStateOf<Territory?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Claimed territories",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${territories.size} claimed · ${formatArea(territories.sumOf { it.areaSqMeters })} total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (territories.isEmpty()) {
                Text(
                    "No claims yet. Close a loop to claim your first territory!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 460.dp),
                ) {
                    items(territories, key = { it.id }) { t ->
                        TerritoryRow(
                            territory = t,
                            onClick = { onSelect(t) },
                            onEdit = { renaming = t },
                            onDelete = { deleting = t },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            currentName = target.name,
            onConfirm = { newName ->
                onRename(target.id, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete territory?") },
            text = {
                Text("“${target.name}” will be removed for good. This can't be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename territory") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A tiny filled drawing of the territory's shape, scaled to fit the box. */
@Composable
private fun PolygonThumbnail(territory: Territory, modifier: Modifier = Modifier) {
    val color = hexColor(territory.colorHex)
    val rings = territory.polygons.flatten().ifEmpty { listOf(territory.ring) }
    Canvas(modifier) {
        val pts = rings.flatten()
        if (pts.size < 3) return@Canvas
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLng = pts.minOf { it.lng }
        val maxLng = pts.maxOf { it.lng }
        // Correct longitude for latitude so the shape isn't horizontally stretched.
        val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0)).toFloat().coerceAtLeast(0.01f)
        val spanLat = (maxLat - minLat).toFloat().coerceAtLeast(1e-7f)
        val spanLng = ((maxLng - minLng).toFloat() * cosLat).coerceAtLeast(1e-7f)

        val pad = size.minDimension * 0.16f
        val boxW = size.width - 2 * pad
        val boxH = size.height - 2 * pad
        val scale = min(boxW / spanLng, boxH / spanLat)
        val drawW = spanLng * scale
        val drawH = spanLat * scale
        val offX = pad + (boxW - drawW) / 2f
        val offY = pad + (boxH - drawH) / 2f

        fun project(p: LatLng): Offset {
            val x = offX + ((p.lng - minLng).toFloat() * cosLat) * scale
            val y = offY + ((maxLat - p.lat).toFloat()) * scale // flip: north is up
            return Offset(x, y)
        }

        val path = Path().apply {
            fillType = PathFillType.EvenOdd // holes render as holes
            rings.forEach { ring ->
                if (ring.size >= 3) {
                    val first = project(ring.first())
                    moveTo(first.x, first.y)
                    ring.drop(1).forEach { val o = project(it); lineTo(o.x, o.y) }
                    close()
                }
            }
        }
        drawPath(path, color.copy(alpha = 0.35f))
        drawPath(path, color, style = Stroke(width = size.minDimension * 0.07f))
    }
}

@Composable
private fun TerritoryRow(
    territory: Territory,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mini snapshot of the claimed shape.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PolygonThumbnail(territory, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(
                territory.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatArea(territory.areaSqMeters)} · ${formatDistance(territory.perimeterMeters)} around · ${territory.ring.size} pts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${formatDate(territory.claimedAtEpochMs)} · ${syncLabel(territory.syncStatus)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename ${territory.name}",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${territory.name}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun syncLabel(status: SyncStatus): String =
    if (status == SyncStatus.SYNCED) "Synced" else "Not synced"

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun TestToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same shape/padding as TerritoryBadge so the two pills match in height.
    Surface(
        modifier = modifier.clickable { onToggle(!enabled) },
        shape = RoundedCornerShape(50),
        color = if (enabled) MaterialTheme.colorScheme.secondary
        else MaterialTheme.colorScheme.surface,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSecondary
        else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
            Text(if (enabled) "Test on" else "Test", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ControlPanel(
    walk: TrackingManager.WalkState,
    testMode: Boolean,
    hasLocationPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    // When the loop is ready, flow the same gradient border used by the claim
    // modal to invite the user to press Stop and claim.
    val readyBorder = if (walk.readyToClose) {
        Modifier.border(BorderStroke(3.dp, rememberFlowingGradient()), shape)
    } else {
        Modifier
    }

    Card(
        modifier = modifier.fillMaxWidth().then(readyBorder),
        shape = shape,
        // Match the claim modal's background (surface + 6dp tonal overlay).
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (testMode) {
                Text(
                    "Test mode on — tap the map to drop points. Return near your start, then press Close loop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            } else if (!hasLocationPermission) {
                Text(
                    "Enclose needs location access to record your walk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (walk.isTracking) {
                LiveStats(walk)
            } else if (!testMode) {
                Text(
                    "Start a walk, wander a loop, and return near your start to claim the area.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (walk.isTracking && walk.readyToClose) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Flag, contentDescription = null)
                        Text("  Close loop")
                    }
                } else if (walk.isTracking) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("  Stop walk")
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("  Start walk")
                    }
                }

                // Recenter the map on the user's current GPS position.
                FilledTonalIconButton(
                    onClick = onRecenter,
                    enabled = hasLocationPermission,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = "Recenter on my location",
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStats(walk: TrackingManager.WalkState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Stat(label = "Walked", value = formatDistance(walk.distanceMeters))
        Stat(
            label = "From start",
            value = walk.distanceToStartMeters?.let { formatDistance(it) } ?: "—",
        )
        Stat(label = "Points", value = walk.path.size.toString())
    }

    // Explain exactly what still blocks closing, so it's never a mystery.
    val remainingPerimeter =
        (TrackingManager.minPerimeterMeters - walk.distanceMeters).roundToInt()
    val hint = when {
        walk.readyToClose ->
            "You're back at the start — press Close loop to claim it!"
        walk.canCloseLoop ->
            "Head into the start zone, then press Stop to claim — ${walk.distanceToStartMeters?.roundToInt() ?: 0} m away"
        !walk.hasLeftStart ->
            "Move at least ${TrackingManager.leaveStartRadiusMeters.roundToInt()} m from your start to begin the loop"
        else ->
            "Keep going — about $remainingPerimeter m more before you can close"
    }
    Text(
        hint,
        style = MaterialTheme.typography.bodyMedium,
        color = if (walk.canCloseLoop) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (walk.canCloseLoop) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Formatting --------------------------------------------------------------

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format("%.2f km", meters / 1000.0)
    else "${meters.roundToInt()} m"

private fun formatArea(sqMeters: Double): String =
    if (sqMeters >= 1_000_000) String.format("%.2f km²", sqMeters / 1_000_000.0)
    else "${sqMeters.roundToInt()} m²"
