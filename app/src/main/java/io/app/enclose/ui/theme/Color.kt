package io.app.enclose.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand seeds. Enclose is about staking ground: a deep territorial purple for
 * claimed land, a warm amber for the trail you're walking, a survey teal for
 * neutral highlights.
 */
internal val BrandPurple = Color(0xFF7B1FA2)
internal val BrandAmber = Color(0xFFF2A65A)

/**
 * Hand-tuned light scheme. Every token is set explicitly — leaving them to the
 * M3 baseline mixed brand purple with stock lavender greys, which read as two
 * different apps depending on which surface you were looking at.
 */
internal val EncloseLightColors = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DEFA),
    onPrimaryContainer = Color(0xFF2E0A40),
    inversePrimary = Color(0xFFD8A7E8),

    secondary = Color(0xFFB06A16),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3C4),
    onSecondaryContainer = Color(0xFF3A2410),

    tertiary = Color(0xFF00796B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB8EDE4),
    onTertiaryContainer = Color(0xFF00201B),

    background = Color(0xFFFCF8FE),
    onBackground = Color(0xFF1D1721),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1721),
    surfaceVariant = Color(0xFFF0E6F4),
    onSurfaceVariant = Color(0xFF574A5E),
    surfaceTint = BrandPurple,

    surfaceDim = Color(0xFFDFD6E4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF4FC),
    surfaceContainer = Color(0xFFF5EDF8),
    surfaceContainerHigh = Color(0xFFEFE5F4),
    surfaceContainerHighest = Color(0xFFE9DEEF),

    inverseSurface = Color(0xFF322A38),
    inverseOnSurface = Color(0xFFF6EDF9),

    outline = Color(0xFF7C6D84),
    outlineVariant = Color(0xFFDDCEE4),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    scrim = Color(0xFF000000),
)

/** The matching dark scheme, kept purple-tinted from background to outline. */
internal val EncloseDarkColors = darkColorScheme(
    primary = Color(0xFFDCACEC),
    onPrimary = Color(0xFF3A0B4F),
    primaryContainer = Color(0xFF5B2A76),
    onPrimaryContainer = Color(0xFFF3DEFB),
    inversePrimary = BrandPurple,

    secondary = BrandAmber,
    onSecondary = Color(0xFF3A2410),
    secondaryContainer = Color(0xFF5A3A1A),
    onSecondaryContainer = Color(0xFFFFDDB8),

    tertiary = Color(0xFF6FD8C6),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046),
    onTertiaryContainer = Color(0xFFB8EDE4),

    background = Color(0xFF110D16),
    onBackground = Color(0xFFEBE2F0),
    surface = Color(0xFF171220),
    onSurface = Color(0xFFEBE2F0),
    surfaceVariant = Color(0xFF382E42),
    onSurfaceVariant = Color(0xFFCFC1D8),
    surfaceTint = Color(0xFFDCACEC),

    surfaceDim = Color(0xFF110D16),
    surfaceBright = Color(0xFF382F41),
    surfaceContainerLowest = Color(0xFF0C0810),
    surfaceContainerLow = Color(0xFF1A1421),
    surfaceContainer = Color(0xFF201829),
    surfaceContainerHigh = Color(0xFF2A2134),
    surfaceContainerHighest = Color(0xFF352B3F),

    inverseSurface = Color(0xFFEBE2F0),
    inverseOnSurface = Color(0xFF322A38),

    outline = Color(0xFF97889F),
    outlineVariant = Color(0xFF4A3E53),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    scrim = Color(0xFF000000),
)

/**
 * Colors that carry app-specific meaning and therefore don't belong to an M3
 * role: the walked trail, the closing zone, GPS quality, "claim succeeded".
 * Read them through [LocalEncloseAccents] so map overlays and UI chips can
 * never drift apart.
 */
@Immutable
data class EncloseAccents(
    /** The live walk path drawn on the map. */
    val trail: Color,
    /** The start anchor dot. */
    val anchor: Color,
    /**
     * The saved home marker. Matches the home button's tint
     * (`colorScheme.primary`) so the control and the pin it flies to read as
     * the same thing, and stays clear of [anchor] so a home pin is never
     * mistaken for the start of a walk.
     */
    val home: Color,
    /** Closing zone once the loop is long enough to claim. */
    val zoneReady: Color,
    /** Closing zone while conditions aren't met yet. */
    val zoneWaiting: Color,
    val gpsGood: Color,
    val gpsFair: Color,
    val gpsPoor: Color,
    val success: Color,
)

internal val LightAccents = EncloseAccents(
    trail = Color(0xFFE07B1F),
    anchor = Color(0xFFE07B1F),
    home = BrandPurple,
    zoneReady = BrandPurple,
    zoneWaiting = Color(0xFF8B8194),
    gpsGood = Color(0xFF2E7D32),
    gpsFair = Color(0xFFB06A16),
    gpsPoor = Color(0xFFB3261E),
    success = Color(0xFF2E7D32),
)

internal val DarkAccents = EncloseAccents(
    trail = BrandAmber,
    anchor = BrandAmber,
    home = Color(0xFFC286DC),
    zoneReady = Color(0xFFC286DC),
    zoneWaiting = Color(0xFF9E93A8),
    gpsGood = Color(0xFF7ED18A),
    gpsFair = BrandAmber,
    gpsPoor = Color(0xFFFFB4AB),
    success = Color(0xFF7ED18A),
)

val LocalEncloseAccents = staticCompositionLocalOf { LightAccents }
