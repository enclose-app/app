package io.app.enclose.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Enclose brand: deep territorial purple.
private val Purple = Color(0xFF7B1FA2)
private val PurpleDark = Color(0xFF4A148C)
private val PurpleLight = Color(0xFFCE93D8)
private val Amber = Color(0xFFF2A65A)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Amber,
    primaryContainer = PurpleLight,
)

private val DarkColors = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color(0xFF2A0A3A),
    secondary = Amber,
    primaryContainer = PurpleDark,
)

@Composable
fun EncloseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
