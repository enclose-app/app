package io.app.enclose.wear

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
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
        // A recreated activity gets no splash, so there is nothing to hold.
        if (savedInstanceState == null) holdSplashUntilReady()
        setContent {
            // Asked once, up front: the ready-to-close prompt is the companion's
            // main job, the steps it counts vouch for the walk, and asking at
            // either moment would be asking mid-walk. Both are optional — the
            // buzz comes without the prompt, and the phone judges on its own
            // steps without the watch's.
            val permissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }
            LaunchedEffect(Unit) {
                permissions.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION),
                )
            }

            val state by link.state.collectAsStateWithLifecycle()
            val map by link.map.collectAsStateWithLifecycle()
            val claims by link.claims.collectAsStateWithLifecycle()
            val phoneApp by link.phoneApp.collectAsStateWithLifecycle()
            EncloseWearTheme {
                WalkScreen(state = state, phoneApp = phoneApp, map = map, claims = claims, link = link)
            }
        }
    }

    /**
     * Keeps the system splash — the app's icon on black, which Wear's quality
     * guidelines require at launch — up until the first read of the phone's
     * status is back. Until then the link reads [LinkState.Waiting], which the
     * screen would show as "open Enclose on your phone" to someone whose phone
     * was connected all along. [PhoneLink] marks a failed read ready too, and
     * [SPLASH_CAP_MS] covers a read that never answers: the splash must never
     * be what stands between the user and the app.
     */
    private fun holdSplashUntilReady() {
        val shownAt = SystemClock.uptimeMillis()
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val go = link.ready.value || SystemClock.uptimeMillis() - shownAt >= SPLASH_CAP_MS
                    if (go) content.viewTreeObserver.removeOnPreDrawListener(this)
                    return go
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        link.attach()
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)) link.showDemo()
    }

    private companion object {
        /** `--ez demo true`: show [DemoWalk] — see there. */
        const val EXTRA_DEMO = "demo"

        /** The longest the splash waits on the phone's status. */
        const val SPLASH_CAP_MS = 3_000L
    }

    override fun onStop() {
        link.detach()
        super.onStop()
    }
}
