package io.app.enclose.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

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
            }

            CoverageCard(percent = stats.cityCoveragePercent)

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
                    DetailRow(
                        "First claim",
                        stats.firstClaimEpochMs?.let { formatDay(it) } ?: EM_DASH,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
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

/**
 * How densely the walker has filled in the region they roam. The metric needs
 * explaining, so the explanation sits with it rather than in a tooltip.
 */
@Composable
private fun CoverageCard(percent: Double) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Region filled in", style = MaterialTheme.typography.titleMedium)
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
                "Your claimed area as a share of the box that contains all of your " +
                    "claims — how completely you've taken the ground you roam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
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
