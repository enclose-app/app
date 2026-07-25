package io.app.enclose.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun EncloseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalEncloseAccents provides if (darkTheme) DarkAccents else LightAccents,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) EncloseDarkColors else EncloseLightColors,
            typography = EncloseTypography,
            shapes = EncloseShapes,
            content = content,
        )
    }
}
