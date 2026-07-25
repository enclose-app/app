package io.app.enclose.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * Tightened type scale. Headlines get real weight and negative tracking so the
 * numbers in the app (areas, paces, distances) read as data rather than prose;
 * labels get positive tracking so small caps-ish text stays legible over the map.
 */
internal val EncloseTypography = Typography(
    displaySmall = Default.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = Default.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = Default.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = Default.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = Default.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = Default.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = Default.bodyMedium.copy(
        lineHeight = 21.sp,
    ),
    labelLarge = Default.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = Default.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = Default.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Style for live-updating figures. Tabular figures stop the stat row from
 * jittering horizontally as digits change every second.
 */
val MetricTextStyle: TextStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum",
)
