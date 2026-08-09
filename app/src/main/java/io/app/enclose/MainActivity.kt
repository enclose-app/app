package io.app.enclose

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.app.enclose.data.SnapDisplay
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.EncloseViewModel
import io.app.enclose.ui.FloatingWalkCard
import io.app.enclose.ui.LocationReadiness
import io.app.enclose.ui.MapScreen
import io.app.enclose.ui.ProfileScreen
import io.app.enclose.ui.Screen
import io.app.enclose.ui.ScreenSaver
import io.app.enclose.ui.SplitScreenSupport
import io.app.enclose.ui.TerritoryDetailScreen
import io.app.enclose.ui.theme.EncloseTheme

class MainActivity : ComponentActivity() {

    /**
     * Whether the app is sharing the screen with another one, PiP excluded.
     *
     * Held as plain state read by the composition rather than queried from it:
     * `isInMultiWindowMode` is a snapshot that nothing recomposes on, so a split
     * that arrives while the map is up would otherwise never reach the UI.
     */
    private val multiWindow = mutableStateOf(false)

    /** True while the app is the small floating window. */
    private val pictureInPicture = mutableStateOf(false)

    /**
     * A track shared into Enclose from another app, waiting to be imported.
     *
     * Held as state rather than read from `getIntent()` in the composition:
     * `singleTask` means a second share arrives at the *running* activity
     * through [onNewIntent], which nothing recomposes on. Cleared by the
     * composition once handed to the ViewModel, so a configuration change can't
     * replay the same file into a second walk.
     */
    private val sharedTrack = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        multiWindow.value = isInMultiWindowMode && !isInPictureInPictureMode
        // Only on a fresh create. After a process kill the system re-delivers the
        // intent that started the task, which would replay a track shared hours
        // ago into a brand new walk.
        if (savedInstanceState == null) sharedTrack.value = trackUriFrom(intent)
        setContent {
            EncloseTheme {
                val viewModel: EncloseViewModel = viewModel()

                val walk by viewModel.walk.collectAsStateWithLifecycle()
                val floatingWindowEnabled by
                    viewModel.floatingWindow.collectAsStateWithLifecycle()

                // True once the user has denied twice (or ticked "don't ask
                // again"): the system prompt will no longer appear, so the UI has
                // to send them to app settings instead of a button that does
                // nothing.
                var permissionBlocked by remember { mutableStateOf(false) }

                // Everything standing between the app and a recordable fix, as
                // one value — see LocationReadiness. Re-read on every resume:
                // permission and the device's location switch can both change
                // while we're backgrounded, and the recovery buttons below send
                // the user out to exactly those screens to change them.
                var location by remember {
                    mutableStateOf(locationReadiness(permissionBlocked))
                }
                RefreshOnResume {
                    if (isPreciseLocationGranted()) permissionBlocked = false
                    location = locationReadiness(permissionBlocked)
                }

                // Auto-enter only while there's a walk to watch: a window that
                // pops up over whatever the user switched to has to be earning
                // its place, and an idle map isn't.
                LaunchedEffect(floatingWindowEnabled, walk.isTracking) {
                    setAutoEnterFloating(floatingWindowEnabled && walk.isTracking)
                }

                // The floating window replaces the whole app rather than shrinking
                // it: at PiP size a map is unreadable, and the GL surface would
                // keep drawing for nothing. The live figures are what someone
                // glances at mid-walk.
                if (pictureInPicture.value) {
                    val territories by viewModel.territories.collectAsStateWithLifecycle()
                    val basemap by viewModel.basemapStyle.collectAsStateWithLifecycle()
                    val plannedRoute by viewModel.plannedRoute.collectAsStateWithLifecycle()
                    FloatingWalkCard(
                        walk = walk,
                        territories = territories,
                        hasLocationPermission = location.hasPermission,
                        basemap = basemap,
                        // The route being followed belongs here more than
                        // anywhere: this window is what's on screen while the
                        // walker is out with the phone in a pocket, glancing at
                        // it to see where the next turn is.
                        plannedRoute = plannedRoute,
                    )
                    return@EncloseTheme
                }

                // Simple state-based navigation — no navigation library. A new
                // destination is added by extending the Screen sealed interface
                // and adding a branch to the when() below. Saved, so rotation
                // and process death don't throw the user back to the map.
                var screen by rememberSaveable(stateSaver = ScreenSaver) {
                    mutableStateOf<Screen>(Screen.Map)
                }

                // A track shared in from another app is imported on arrival —
                // that is the whole point of the share, so making the user find a
                // button afterwards would only be a step in the way.
                //
                // On the map, deliberately: the map is what frames the imported
                // route and offers the Stop that turns it into a claim, and the
                // detail screen draws no import progress at all. Sits after the
                // picture-in-picture branch above, so a share arriving while the
                // window is floating waits (state, not an event) and lands the
                // moment the app is full-screen again.
                LaunchedEffect(sharedTrack.value) {
                    val uri = sharedTrack.value ?: return@LaunchedEffect
                    sharedTrack.value = null
                    screen = Screen.Map
                    viewModel.importGpx(uri)
                }

                // One-shot hand-offs from the detail screen back to the map:
                // a set of points to frame, and a territory to delete-with-undo.
                var pendingFocus by remember {
                    mutableStateOf<List<io.app.enclose.geo.LatLng>?>(null)
                }
                var pendingDelete by remember {
                    mutableStateOf<io.app.enclose.data.Territory?>(null)
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    // Precise only. The Android 12+ dialog offers Approximate
                    // right beside it, and taking that option leaves every fix
                    // too vague for TrackingManager to keep — a granted
                    // permission that can't record a metre.
                    val precise = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    permissionBlocked = !precise && !ActivityCompat
                        .shouldShowRequestPermissionRationale(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    location = locationReadiness(permissionBlocked)
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
                            location = location,
                            onOpenLocationSettings = ::openLocationSettings,
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
                            inMultiWindow = multiWindow.value,
                            splitScreenSupported = supportsSplitScreenRequest(),
                            onRequestSplitScreen = ::requestSplitScreen,
                            floatingWindowEnabled = floatingWindowEnabled,
                            onSetFloatingWindow = viewModel::setFloatingWindow,
                            floatingWindowSupported = supportsFloatingWindow(),
                            onEnterFloatingWindow = ::enterFloatingWindow,
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
                                // Frame whatever is actually drawn — see SnapDisplay.
                                pendingFocus = SnapDisplay.pointsFor(territory)
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

    /**
     * A share that lands while Enclose is already running. `singleTask` routes it
     * here instead of starting a second copy of the app, so without this the
     * second track someone shares would do nothing at all.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // So anything that later reads getIntent() sees the one that's current.
        setIntent(intent)
        trackUriFrom(intent)?.let { sharedTrack.value = it }
    }

    /**
     * The track in an incoming share or "open with", or null when the intent
     * isn't carrying one.
     *
     * Nothing is inspected beyond the uri: the mime type a provider claims for a
     * GPX is unreliable enough that the filters in the manifest already accept
     * four of them, and [io.app.enclose.ui.EncloseViewModel.importGpx] reports a
     * file with no track points in it far better than a silent no-op here would.
     */
    private fun trackUriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        // PiP reports itself as multi-window; the map's split-screen control
        // cares about sharing the screen with another app, which is different.
        multiWindow.value = isInMultiWindowMode && !isInPictureInPictureMode
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) multiWindow.value = false
    }

    /**
     * Ask the system to put Enclose in a split-screen half.
     *
     * There is **no public API for an app to enter split screen**, so this is a
     * request, not a command: relaunching ourselves with
     * `FLAG_ACTIVITY_LAUNCH_ADJACENT` is honoured by Samsung's One UI and
     * ignored on stock Android, where the flag is documented as doing nothing
     * unless the app is already in split. Ignored, it is a deliberate no-op —
     * `singleTask` means the intent is delivered to the running activity instead
     * of starting a second copy of the app.
     *
     * Returns false whenever the caller should go straight to explaining the
     * Recents route: because the device won't honour the flag
     * ([SplitScreenSupport]), or because the launch itself failed.
     */
    private fun requestSplitScreen(): Boolean {
        // Deliberately *not* skipped when already split. Re-issuing the request
        // from inside a split is the one case AOSP documents the flag for: the
        // system re-pairs this task into the adjacent stack, which tears down
        // whatever pairing was there before. That's the only lever an app has on
        // an existing split — there's no API to dismantle one directly — so the
        // control stays live rather than being a dead button once you're in one.
        //
        // Guarded here as well as by hiding the control: the request costs the
        // user a pause and then the same dialog, so a device that will never
        // answer skips straight to it.
        if (!supportsSplitScreenRequest()) return false
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
        )
        return runCatching { startActivity(intent) }.isSuccess
    }

    /**
     * Whether asking for split screen can land on this device. Hides the control
     * where it can't, since a button that never works is worse than no button.
     */
    private fun supportsSplitScreenRequest(): Boolean =
        SplitScreenSupport.honoursAdjacentLaunch(
            alreadyMultiWindow = isInMultiWindowMode && !isInPictureInPictureMode,
            manufacturer = Build.MANUFACTURER,
            hasSamsungMultiWindow = packageManager.hasSystemFeature(
                SplitScreenSupport.SAMSUNG_MULTIWINDOW_FEATURE,
            ),
        )

    /** False on devices (and profiles) where picture-in-picture doesn't exist. */
    private fun supportsFloatingWindow(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Float the walk over whatever the user does next, without them having to
     * ask again each time they leave the app. Only ever true while the
     * preference is on *and* a walk is running — see the effect in [onCreate].
     */
    private fun setAutoEnterFloating(enabled: Boolean) {
        if (!supportsFloatingWindow()) return
        // Failures here are the system declining (PiP switched off for this app
        // in settings, or the activity already finishing); none of them is worth
        // interrupting a walk over.
        runCatching { setPictureInPictureParams(floatingParams(autoEnter = enabled)) }
    }

    /**
     * Enter the floating window now. Returns false when the system refused —
     * picture-in-picture can be turned off per app in system settings, and a
     * button that silently does nothing there is worse than one that says so.
     */
    private fun enterFloatingWindow(): Boolean {
        if (!supportsFloatingWindow()) return false
        return runCatching { enterPictureInPictureMode(floatingParams(autoEnter = true)) }
            .getOrDefault(false)
    }

    private fun floatingParams(autoEnter: Boolean): PictureInPictureParams {
        // Where the window shrinks from. The whole content area, because that is
        // what's shrinking — the card that appears is a different layout, not a
        // crop of the map, so hinting at any one part of it would promise an
        // animation the content can't deliver.
        val source = Rect().also { window.decorView.getGlobalVisibleRect(it) }
        return PictureInPictureParams.Builder()
            // 16:9 landscape: the card is two lines of figures, and a taller
            // window would only cover more of whatever is underneath it.
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(autoEnter)
            .setSourceRectHint(source)
            // The card is a different layout, not a scaled-down map, so letting
            // the system cross-fade the resize is the honest animation.
            .setSeamlessResizeEnabled(false)
            .build()
    }

    /**
     * Precise location. Approximate is *not* enough to record a walk: those fixes
     * land hundreds of metres out and [TrackingManager.MAX_ACCURACY_METERS]
     * discards every one, so a walk under approximate-only ran a foreground
     * service, held a notification and recorded nothing.
     */
    private fun isPreciseLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun isApproximateLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * The device's own location switch. With it off, subscribing to fixes
     * *succeeds* and then never delivers a callback — nothing throws, nothing
     * reports, and the walk records nothing forever. Checking it is the only way
     * to know.
     */
    private fun isLocationServicesEnabled(): Boolean =
        runCatching {
            LocationManagerCompat.isLocationEnabled(
                getSystemService(LocationManager::class.java),
            )
        }.getOrDefault(true)

    private fun locationReadiness(promptBlocked: Boolean): LocationReadiness =
        LocationReadiness.of(
            precise = isPreciseLocationGranted(),
            approximate = isApproximateLocationGranted(),
            servicesEnabled = isLocationServicesEnabled(),
            promptBlocked = promptBlocked,
        )

    /** Deep-link to the device's location settings, for the master switch. */
    private fun openLocationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }.onFailure { openAppSettings() }
    }

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
