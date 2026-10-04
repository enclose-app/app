package io.app.enclose.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val link by lazy { PhoneLink(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Asked once, up front: the ready-to-close prompt is the companion's
            // main job, and asking at that moment would be asking mid-walk.
            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

            val state by link.state.collectAsStateWithLifecycle()
            val map by link.map.collectAsStateWithLifecycle()
            val claims by link.claims.collectAsStateWithLifecycle()
            val ready by link.ready.collectAsStateWithLifecycle()
            EncloseWearTheme {
                LaunchLogo(ready = ready) {
                    WalkScreen(state = state, map = map, claims = claims, link = link)
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
