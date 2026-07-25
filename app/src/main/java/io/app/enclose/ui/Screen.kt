package io.app.enclose.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * The set of top-level destinations. Navigation is a plain state switch in
 * [io.app.enclose.MainActivity] (no navigation library) — add a destination by
 * adding a case here, a branch to the when() in MainActivity, and an entry to
 * [ScreenSaver].
 */
sealed interface Screen {
    data object Map : Screen
    data object Profile : Screen
    data class TerritoryDetail(val id: String) : Screen
}

/**
 * Persists the current destination across rotation and process death. Without
 * it, turning the phone sideways on a territory dropped the user back on the map.
 */
val ScreenSaver: Saver<Screen, Any> = listSaver(
    save = { screen ->
        when (screen) {
            Screen.Map -> listOf(KEY_MAP)
            Screen.Profile -> listOf(KEY_PROFILE)
            is Screen.TerritoryDetail -> listOf(KEY_TERRITORY, screen.id)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            KEY_PROFILE -> Screen.Profile
            KEY_TERRITORY -> saved.getOrNull(1)?.let { Screen.TerritoryDetail(it) } ?: Screen.Map
            else -> Screen.Map
        }
    },
)

private const val KEY_MAP = "map"
private const val KEY_PROFILE = "profile"
private const val KEY_TERRITORY = "territory"
