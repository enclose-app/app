package io.app.enclose.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val link by lazy { PhoneLink(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system splash normally fades itself out over the app's first
        // frame. That frame is LaunchLogo — the same icon in the same place —
        // so the fade showed as the icon dimming and coming back. Taking the
        // splash away in one step leaves nothing to see change.
        splashScreen.setOnExitAnimationListener { it.remove() }
        setContent {
            // Asked once, up front: the ready-to-close prompt is the companion's
            // main job, the steps it counts vouch for the walk, and asking at
            // either moment would be asking mid-walk. Both are optional — the
            // buzz comes without the prompt, and the phone judges on its own
            // steps without the watch's.
            var notificationsAllowed by remember { mutableStateOf(false) }
            val permissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { notificationsAllowed = it[Manifest.permission.POST_NOTIFICATIONS] == true }
            // Not before the launch logo has gone: the prompt is a window of
            // its own, and asked at once it covered the logo on a first run —
            // the run a reviewer sees.
            var launched by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(launched) {
                if (launched) {
                    permissions.launch(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION),
                    )
                }
            }

            val state by link.state.collectAsStateWithLifecycle()
            // The listener service posts the walk's indicator as statuses
            // arrive, but one that arrived before notifications were allowed
            // posted nothing. Granting them mid-walk shouldn't have to wait for
            // the next ten metres.
            val known = (state as? LinkState.Known)?.status
            LaunchedEffect(known, notificationsAllowed) {
                if (known != null) WalkIndicator.show(this@MainActivity, known)
            }
            val map by link.map.collectAsStateWithLifecycle()
            val claims by link.claims.collectAsStateWithLifecycle()
            val phoneApp by link.phoneApp.collectAsStateWithLifecycle()
            val ready by link.ready.collectAsStateWithLifecycle()
            EncloseWearTheme {
                LaunchLogo(ready = ready, onDone = { launched = true }) {
                    WalkScreen(state = state, phoneApp = phoneApp, map = map, claims = claims, link = link)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        link.attach()
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)) link.showDemo()
    }

    private companion object {
        /** `--ez demo true`: show [DemoWalk] — see there. */
        const val EXTRA_DEMO = "demo"
    }

    override fun onStop() {
        link.detach()
        super.onStop()
    }
}
