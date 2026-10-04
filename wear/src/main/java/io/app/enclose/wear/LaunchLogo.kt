package io.app.enclose.wear

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay

/**
 * The app's mark, large, while the first read of the phone's status is out —
 * then [content].
 *
 * Wear's system splash gives an icon a fixed 48dp whatever the screen, which is
 * a small mark on a watch, so the theme makes that splash plain black and the
 * logo is drawn here instead, at a share of the screen rather than a size in dp.
 * The share is what keeps it right across watches: [ART_FRACTION] of the
 * shorter side is the same look on a 40 mm face as on a 47 mm one, and on a
 * round face as on a square one. Past about 0.8 the bezel starts to cut the
 * hexagon's corners.
 *
 * Waiting on the read is the point, not only a frame to fill: until it returns
 * the link reads [LinkState.Waiting], which the screen used to show as "open
 * Enclose on your phone" to someone whose phone was connected all along.
 */
@Composable
fun LaunchLogo(ready: Boolean, content: @Composable () -> Unit) {
    // Held for a moment even when the read is instant, so the logo is seen
    // rather than flashed. Saved, so a recreated activity doesn't show it again.
    var held by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(MIN_SHOWN_MS)
        held = false
    }
    Crossfade(targetState = ready && !held, label = "launch") { done ->
        if (done) content() else Logo()
    }
}

@Composable
private fun Logo() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // splash_icon draws its mark across ART_SPAN of its width.
        val side = minOf(maxWidth, maxHeight) * (ART_FRACTION / ART_SPAN)
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = null,
            modifier = Modifier.size(side),
        )
    }
}

/** Share of the screen's shorter side the mark spans. */
private const val ART_FRACTION = 0.6f

/** Share of splash_icon's width its mark spans: 66 of 108 units. */
private const val ART_SPAN = 66f / 108f

private const val MIN_SHOWN_MS = 500L
