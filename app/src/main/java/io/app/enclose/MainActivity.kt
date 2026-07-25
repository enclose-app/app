package io.app.enclose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.ui.EncloseViewModel
import io.app.enclose.ui.MapScreen
import io.app.enclose.ui.ProfileScreen
import io.app.enclose.ui.Screen
import io.app.enclose.ui.ScreenSaver
import io.app.enclose.ui.TerritoryDetailScreen
import io.app.enclose.ui.theme.EncloseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncloseTheme {
                val viewModel: EncloseViewModel = viewModel()

                // Simple state-based navigation — no navigation library. A new
                // destination is added by extending the Screen sealed interface
                // and adding a branch to the when() below. Saved, so rotation
                // and process death don't throw the user back to the map.
                var screen by rememberSaveable(stateSaver = ScreenSaver) {
                    mutableStateOf<Screen>(Screen.Map)
                }

                // One-shot hand-offs from the detail screen back to the map:
                // a set of points to frame, and a territory to delete-with-undo.
                var pendingFocus by remember {
                    mutableStateOf<List<io.app.enclose.geo.LatLng>?>(null)
                }
                var pendingDelete by remember {
                    mutableStateOf<io.app.enclose.data.Territory?>(null)
                }

                var hasLocationPermission by remember { mutableStateOf(isLocationGranted()) }
                // True once the user has denied twice (or ticked "don't ask
                // again"): the system prompt will no longer appear, so the UI has
                // to send them to app settings instead of a button that does
                // nothing.
                var permissionBlocked by remember { mutableStateOf(false) }

                // Permission can change while we're backgrounded (system
                // settings, another app's prompt), so re-read it on every resume.
                RefreshOnResume {
                    val granted = isLocationGranted()
                    hasLocationPermission = granted
                    if (granted) permissionBlocked = false
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    val granted =
                        result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    hasLocationPermission = granted
                    permissionBlocked = !granted && !ActivityCompat
                        .shouldShowRequestPermissionRationale(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                }

                // System back returns to the map from any sub-screen.
                BackHandler(enabled = screen != Screen.Map) { screen = Screen.Map }

                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // Sub-screens slide in from the trailing edge; going back
                        // to the map reverses it.
                        if (targetState == Screen.Map) {
                            fadeIn() togetherWith
                                slideOutHorizontally { it / 5 } + fadeOut()
                        } else {
                            slideInHorizontally { it / 5 } + fadeIn() togetherWith fadeOut()
                        }
                    },
                    label = "screen",
                ) { current ->
                    when (current) {
                        Screen.Map -> MapScreen(
                            viewModel = viewModel,
                            hasLocationPermission = hasLocationPermission,
                            permissionBlocked = permissionBlocked,
                            onRequestPermission = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                        // Optional: lets Enclose tell walking and
                                        // cycling from driving. Declining leaves
                                        // the speed-based checks in place.
                                        Manifest.permission.ACTIVITY_RECOGNITION,
                                    ),
                                )
                            },
                            onOpenAppSettings = ::openAppSettings,
                            onOpenProfile = { screen = Screen.Profile },
                            onOpenTerritory = { id -> screen = Screen.TerritoryDetail(id) },
                            pendingFocus = pendingFocus,
                            onFocusConsumed = { pendingFocus = null },
                            pendingDelete = pendingDelete,
                            onDeleteConsumed = { pendingDelete = null },
                        )

                        Screen.Profile -> ProfileScreen(
                            onBack = { screen = Screen.Map },
                        )

                        is Screen.TerritoryDetail -> TerritoryDetailScreen(
                            territoryId = current.id,
                            onBack = { screen = Screen.Map },
                            onShowOnMap = { territory ->
                                val pts = territory.polygons.flatten().flatten()
                                    .ifEmpty { territory.ring }
                                pendingFocus = pts
                                screen = Screen.Map
                            },
                            onDelete = { territory ->
                                pendingDelete = territory
                                screen = Screen.Map
                            },
                        )
                    }
                }
            }
        }
    }

    private fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /** Deep-link to this app's settings page so a blocked permission is fixable. */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }
}

/** Runs [onResume] on every ON_RESUME event of the hosting lifecycle. */
@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
