package io.app.enclose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.app.enclose.data.SyncStatus
import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import io.app.enclose.ui.theme.LocalEncloseAccents
import io.app.enclose.ui.theme.MetricTextStyle
import io.app.enclose.ui.theme.PillShape
import kotlin.math.cos
import kotlin.math.min

/**
 * Shared building blocks. Anything that appears on more than one screen lives
 * here so the map, the list sheet, the detail view and the profile can't drift
 * into three different visual languages.
 *
 * Touch targets are held at [TOUCH_TARGET] (48dp) throughout — several of the
 * originals were 34–40dp, which is below the accessibility minimum.
 */

/** Android/Material minimum touch target. */
val TOUCH_TARGET = 48.dp

// --- Buttons -----------------------------------------------------------------

/**
 * Icon + label content for the Button family. Replaces the `Text("  Claim")`
 * two-space trick, which produced inconsistent gaps and read the padding out
 * loud in TalkBack.
 */
@Composable
fun RowScope.ButtonContent(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// --- Floating controls over the map ------------------------------------------

/**
 * Translucent container for controls that float on top of the map. Slightly
 * see-through so the map stays readable underneath, with a hairline border so
 * the edge survives against both pale streets and dark parks.
 */
@Composable
fun MapSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = PillShape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            shape = shape,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        content = content,
    )
}

/** A 48dp circular map control (recenter, zoom, …). */
@Composable
fun MapControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    MapSurface(
        modifier = modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A labelled pill for the map's top row (claim count, test-mode marker, …). */
@Composable
fun MapChip(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier
            .height(TOUCH_TARGET)
            .clip(PillShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = contentDescription,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = PillShape,
            ),
        shape = PillShape,
        color = container,
        contentColor = content,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

// --- Cards & stats -----------------------------------------------------------

/** Titled card used for every grouped block on the detail and profile screens. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * Value-over-label tile. [accent] tints the value; pass the territory color on
 * the detail screen so the numbers belong to the thing they describe.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = container,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                value,
                style = MetricTextStyle,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact centred metric for the live walk row. */
@Composable
fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MetricTextStyle, color = accent, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Label on the left, value on the right — for read-only detail rows. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

// --- Progress ----------------------------------------------------------------

/** Rounded, animated progress track. [progress] is clamped for safety. */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    thickness: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "progress",
    )
    val animatedColor by animateColorAsState(color, tween(400), label = "progressColor")
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(PillShape)
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(thickness)
                .clip(PillShape)
                .background(animatedColor),
        )
    }
}

/**
 * The purple→amber gradient that signals "this loop is ready to claim". Shared
 * by the claim dialog border and the control panel so the two read as the same
 * moment in the flow.
 */
@Composable
fun rememberFlowingGradient(): Brush {
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

// --- Color picking -----------------------------------------------------------

/** A row of 48dp color targets; the selected one gets a ring and a checkmark. */
@Composable
fun ColorPickerRow(
    selectedHex: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CLAIM_PALETTE.forEach { hex ->
            ColorSwatch(hex = hex, selected = hex == selectedHex, onClick = { onSelect(hex) })
        }
    }
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = hexColor(hex)
    Box(
        modifier = Modifier
            .size(TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(
                onClickLabel = if (selected) "Selected color" else "Choose color",
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Selection ring sits outside the swatch so the swatch size never jumps.
        if (selected) {
            Box(
                Modifier
                    .size(TOUCH_TARGET)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        }
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// --- Territory bits ----------------------------------------------------------

/**
 * A filled drawing of the territory's shape, scaled to fit the box. Reused at
 * thumbnail size in the list and at hero size on the detail screen.
 */
@Composable
fun PolygonThumbnail(territory: Territory, modifier: Modifier = Modifier) {
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

        val pad = size.minDimension * 0.14f
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
        drawPath(path, color.copy(alpha = 0.32f))
        // Proportional so 48dp thumbnails still read, capped so the hero-size
        // preview gets a crisp outline instead of a slab.
        val stroke = (size.minDimension * 0.055f).coerceIn(2f, 10f)
        drawPath(path, color, style = Stroke(width = stroke))
    }
}

/** Dot + word describing whether a claim has reached the backend. */
@Composable
fun SyncBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val accents = LocalEncloseAccents.current
    val synced = status == SyncStatus.SYNCED
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (synced) accents.success else MaterialTheme.colorScheme.outline),
        )
        Text(
            syncLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Empty state -------------------------------------------------------------

/** Centred icon + copy for "nothing here yet" areas. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

// --- Dialogs -----------------------------------------------------------------

/**
 * One text-entry dialog for every rename in the app (there were three
 * near-identical copies). Trims on confirm and refuses blank input.
 */
@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirmation dialog; [destructive] paints the confirm button with the error role. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Single-button dialog for telling the user something they can't act on. */
@Composable
fun NoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    dismissLabel: String = "Got it",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Round avatar showing profile initials, used in the map top bar and profile. */
@Composable
fun InitialsAvatar(
    initials: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = if (size > 48.dp) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Fills the given box with the territory shape on a soft tint of its color. */
@Composable
fun TerritoryTile(territory: Territory, modifier: Modifier = Modifier) {
    val color = hexColor(territory.colorHex)
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.12f)),
    ) {
        PolygonThumbnail(territory, Modifier.fillMaxSize())
    }
}

/** Remembered, stable "now" for relative timestamps within one composition. */
@Composable
fun rememberNow(): Long = remember { System.currentTimeMillis() }
