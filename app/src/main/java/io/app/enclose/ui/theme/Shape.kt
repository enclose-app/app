package io.app.enclose.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One rounding scale for the whole app. Before this, radii were written inline
 * per call site (10, 14, 16, 18, 20, 24, 28 dp) and neighbouring cards rarely
 * agreed. Use `MaterialTheme.shapes.*`; only pills stay hard-coded to 50%.
 */
internal val EncloseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Fully rounded, for chips and floating map controls. */
val PillShape = RoundedCornerShape(percent = 50)
