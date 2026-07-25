package io.app.enclose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.app.enclose.data.Territory
import io.app.enclose.ui.theme.PillShape

/** Ways to order the claim list. */
private enum class TerritorySort(val label: String) {
    RECENT("Recent"),
    LARGEST("Largest"),
    NAME("A–Z"),
}

/** Below this many claims, search would be more friction than help. */
private const val SEARCH_THRESHOLD = 6

/**
 * The list of claimed territories, opened from the map's claims chip.
 *
 * Adds search and sorting (the previous version was a fixed 460dp-tall,
 * always-newest-first list with no way to find anything once you had a dozen
 * claims), and moves the per-row destructive actions behind an overflow menu so
 * a mis-tap can't delete a claim while scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryListSheet(
    territories: List<Territory>,
    onDismiss: () -> Unit,
    onSelect: (Territory) -> Unit,
    onShowOnMap: (Territory) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (Territory) -> Unit,
) {
    var renaming by remember { mutableStateOf<Territory?>(null) }
    var deleting by remember { mutableStateOf<Territory?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TerritorySort.RECENT) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = rememberNow()

    val visible = remember(territories, query, sort) {
        territories
            .filter { it.name.contains(query.trim(), ignoreCase = true) }
            .let { list ->
                when (sort) {
                    TerritorySort.RECENT -> list.sortedByDescending { it.claimedAtEpochMs }
                    TerritorySort.LARGEST -> list.sortedByDescending { it.areaSqMeters }
                    TerritorySort.NAME -> list.sortedBy { it.name.lowercase() }
                }
            }
    }

    // The list is capped rather than the sheet being forced tall, so a couple of
    // claims produce a compact sheet and a long list still scrolls.
    val listMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Your territories", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    label = "Claimed",
                    value = territories.size.toString(),
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.primary,
                )
                StatTile(
                    label = "Total area",
                    value = formatArea(territories.sumOf { it.areaSqMeters }),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Total edge",
                    value = formatDistance(territories.sumOf { it.perimeterMeters }),
                    modifier = Modifier.weight(1f),
                )
            }

            if (territories.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Flag,
                    title = "No claims yet",
                    message = "Close your first loop and it will show up here with its " +
                        "shape, size and history.",
                )
                return@Column
            }

            Spacer(Modifier.height(16.dp))

            if (territories.size >= SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search by name") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerritorySort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option.label) },
                        shape = PillShape,
                        // Brand purple for selection; the default amber-ish
                        // secondary container reads as a warning here.
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "Nothing matches “${query.trim()}”",
                    message = "Try a different name, or clear the search.",
                )
                return@Column
            }

            LazyColumn(Modifier.heightIn(max = listMaxHeight)) {
                itemsIndexed(visible, key = { _, t -> t.id }) { index, territory ->
                    TerritoryRow(
                        territory = territory,
                        now = now,
                        onClick = { onSelect(territory) },
                        onShowOnMap = { onShowOnMap(territory) },
                        onRename = { renaming = territory },
                        onDelete = { deleting = territory },
                    )
                    // Dividers between rows only — a trailing one reads as a
                    // cut-off list.
                    if (index != visible.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        TextEntryDialog(
            title = "Rename territory",
            label = "Name",
            initialValue = target.name,
            onConfirm = { newName ->
                onRename(target.id, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "Delete territory?",
            message = "“${target.name}” will be removed. You can undo right after.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                onDelete(target)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun TerritoryRow(
    territory: Territory,
    now: Long,
    onClick: () -> Unit,
    onShowOnMap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open ${territory.name}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerritoryTile(territory, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                territory.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatArea(territory.areaSqMeters)} · " +
                    "${formatDistance(territory.perimeterMeters)} around",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRelativeDay(territory.claimedAtEpochMs, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                SyncBadge(territory.syncStatus)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(TOUCH_TARGET)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${territory.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Show on map") },
                    leadingIcon = { Icon(Icons.Filled.Map, null) },
                    onClick = {
                        menuOpen = false
                        onShowOnMap()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
