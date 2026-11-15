package io.app.enclose.wear

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
 * twice: once with the system's icon turned off, once with it on.
 *
 * So both are kept, and made to look like one. The system splash shows the
 * icon when it will; this draws the same drawable at the same size in the same
 * place, so from the launcher the two run together, and from anywhere else
 * this is where the icon appears.
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
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.splash_icon),
                    contentDescription = null,
                    modifier = Modifier.size(SYSTEM_SPLASH_ICON),
                )
            }
        }
    }
}

/**
 * The size the system splash draws splash_icon at, so the hand-over from one
 * to the other doesn't jump. The mark spans 66 of the drawable's 108 units,
 * which makes it the 48dp the guidelines ask of the icon.
 */
private val SYSTEM_SPLASH_ICON = 90.dp

private const val MIN_SHOWN_MS = 800L
private const val MAX_SHOWN_MS = 3_000L
private const val POLL_MS = 50L
