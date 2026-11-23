package io.app.enclose.wear

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.delay

/**
 * The app's icon on black, as the first thing the app itself draws — then
 * [content].
 *
 * Wear's quality guidelines require the app icon on the launch screen, and the
 * system splash (themes.xml) cannot be relied on to be it: Android draws that
 * splash with its icon only for a start from the launcher or the system. Opened
 * by any other app — Play's own "Open" button, which is how a fresh install is
 * usually first started, review included — it is the background colour alone.
 * Holding that splash while the phone answered, as this used to, made it three
 * seconds of plain black, and it was rejected at review for the missing icon
 * twice: once with the system's icon turned off, once with it on. The theme now
 * sets `windowSplashScreenBehavior="icon_preferred"`, which asks for the icon
 * on every start, not only the launcher's.
 *
 * So both are kept, and made to look like one. The system splash shows the
 * icon; this draws the same drawable over the whole screen, as the system
 * does, and splash_icon centres a 48dp launcher icon in whatever it is given —
 * so the two run together, and where a caller forces a plain splash anyway
 * this is where the icon appears. It goes through a Canvas because
 * painterResource can't draw a layer-list or an adaptive icon.
 *
 * It stays until [ready] — the first read of the phone's status. Until then
 * the link reads [LinkState.Waiting], which the screen would show as "open
 * Enclose on your phone" to someone whose phone was connected all along.
 * [MIN_SHOWN_MS] keeps it from being a flash when the read is instant, and
 * [MAX_SHOWN_MS] covers a read that never answers: the logo must never be what
 * stands between the user and the app. [onDone] is called once it has gone —
 * anything that would cover it (a permission prompt) waits for that.
 */
@Composable
fun LaunchLogo(ready: Boolean, onDone: () -> Unit, content: @Composable () -> Unit) {
    // Saved, so a recreated activity doesn't show it again.
    var done by rememberSaveable { mutableStateOf(false) }
    val isReady by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        if (!done) {
            delay(MIN_SHOWN_MS)
            var waited = MIN_SHOWN_MS
            while (!isReady && waited < MAX_SHOWN_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            done = true
        }
        onDone()
    }
    Crossfade(targetState = done, label = "launch") { showContent ->
        if (showContent) {
            content()
        } else {
            val res = LocalResources.current
            val icon = remember(res) { res.getDrawable(R.drawable.splash_icon, null) }
            Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (icon != null) {
                    drawIntoCanvas { canvas ->
                        icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        icon.draw(canvas.nativeCanvas)
                    }
                }
            }
        }
    }
}

private const val MIN_SHOWN_MS = 800L
private const val MAX_SHOWN_MS = 3_000L
private const val POLL_MS = 50L
