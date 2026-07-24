package io.app.enclose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.app.enclose.data.Territory
import io.app.enclose.tracking.TrackingManager
import kotlinx.coroutines.launch
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
                modifier = Modifier.fillMaxSize(),
            )

            // Top-left: territory count.
            TerritoryBadge(
                count = territories.size,
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
private fun TerritoryBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (testMode) {
                Text(
                    "Test mode on — tap the map to drop points. Tap back near your first point to close the loop.",
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
                if (walk.isTracking) {
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
        (TrackingManager.MIN_PERIMETER_METERS - walk.distanceMeters).roundToInt()
    val hint = when {
        walk.canCloseLoop ->
            "Head into the start zone to claim — ${walk.distanceToStartMeters?.roundToInt() ?: 0} m away"
        !walk.hasLeftStart ->
            "Move at least ${TrackingManager.LEAVE_START_RADIUS_METERS.roundToInt()} m from your start to begin the loop"
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
