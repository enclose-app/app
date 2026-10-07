package io.app.enclose.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.app.enclose.watchlink.WatchClaims
import io.app.enclose.watchlink.WatchFormat
import io.app.enclose.watchlink.WatchLink
import io.app.enclose.watchlink.WatchMap
import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchStatus.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The icon's palette: neon violet on near-black. See play/icon.svg. */
private val Violet = Color(0xFFAE4FF8)
private val Magenta = Color(0xFFDD59F8)
private val Body = Color(0xFF190F1F)

@Composable
fun EncloseWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Violet,
            onPrimary = Body,
            primaryContainer = Violet,
            onPrimaryContainer = Body,
            tertiary = Magenta,
            background = Color.Black,
        ),
        content = content,
    )
}

/**
 * One screen that follows the phone: start, the walk, the closed loop. The
 * phone is the authority on every figure here; the watch only counts the clock
 * up between updates.
 */
@Composable
fun WalkScreen(state: LinkState, map: WatchMap?, claims: WatchClaims?, link: PhoneLink) {
    AppScaffold {
        val walking = (state as? LinkState.Known)?.status?.takeIf { it.phase == Phase.WALKING }
        if (walking != null) {
            // A map left over from the previous walk would draw its path under
            // this one until the phone's next map arrives; the start time says
            // which walk a map belongs to.
            WalkingMap(
                status = walking,
                map = map?.takeIf { it.startedAtEpochMs == walking.startedAtEpochMs },
                claims = claims,
                link = link,
            )
            return@AppScaffold
        }
        ScreenScaffold {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    LinkState.Waiting -> NoPhone(link)
                    LinkState.Unreadable -> Message(
                        title = stringResource(R.string.watch_update_title),
                        body = stringResource(R.string.watch_update_body),
                    )
                    is LinkState.Known -> when (state.status.phase) {
                        Phase.IDLE -> Idle(link)
                        Phase.WALKING -> Unit // drawn above, full-screen
                        Phase.CLOSED -> Closed(state.status, link)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoPhone(link: PhoneLink) {
    Message(title = stringResource(R.string.app_name), body = stringResource(R.string.watch_no_phone_body))
    Spacer(Modifier.height(8.dp))
    OpenOnPhoneButton(label = stringResource(R.string.watch_open_on_phone), uri = WatchLink.OPEN_URI, link = link)
}

@Composable
private fun Idle(link: PhoneLink) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = Violet)
    Spacer(Modifier.height(10.dp))
    OpenOnPhoneButton(label = stringResource(R.string.watch_start_walk), uri = WatchLink.START_URI, link = link)
    Spacer(Modifier.height(6.dp))
    Text(
        // Said up front so the phone lighting up in a pocket isn't a surprise:
        // Android only lets the walk start there.
        stringResource(R.string.watch_starts_on_phone),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

/**
 * The walk: the live map, full-screen, with the figures in a strip across the
 * lower part of the circle — the widest band a round screen has below the
 * clock — and Stop there once stopping would claim.
 */
@Composable
private fun WalkingMap(status: WatchStatus, map: WatchMap?, claims: WatchClaims?, link: PhoneLink) {
    // The clock is counted here, not sent: a status per second would keep the
    // Bluetooth link awake for a number the watch can work out itself.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = status.startedAtEpochMs?.let { WatchFormat.elapsed(now - it) } ?: "--:--"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WalkMap(
            map = map,
            claims = claims,
            readyToClose = status.readyToClose,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim, Scrim)))
                .padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    R.string.watch_walk_figures,
                    elapsed,
                    LocalResources.current.formatDistance(status.distanceMeters),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (status.canStopFromWatch) {
                Spacer(Modifier.height(4.dp))
                StopButton(link)
            } else {
                Text(
                    walkingHint(status),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = if (status.notRecording || status.blocked) MaterialTheme.colorScheme.error
                    else Color.Unspecified,
                )
            }
            // OpenStreetMap's data, OpenFreeMap's tiles: the licence asks for
            // this to be visible, and MapLibre's own button needs a corner.
            Text(
                stringResource(R.string.watch_osm_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/** Behind the strip, so the figures read over a busy map. */
private val Scrim = Color(0xD9000000)

/** One line on what the walk needs next, most urgent first. */
@Composable
private fun walkingHint(status: WatchStatus): String = when {
    status.notRecording -> stringResource(R.string.watch_hint_not_recording)
    status.blocked -> stringResource(R.string.watch_hint_vehicle)
    status.startedAtEpochMs == null -> stringResource(R.string.watch_hint_finding_gps)
    status.readyToClose -> stringResource(R.string.watch_hint_ready)
    status.canCloseLoop -> status.toStartMeters
        ?.let {
            stringResource(
                R.string.watch_hint_head_back_distance,
                LocalResources.current.formatDistance(it),
            )
        }
        ?: stringResource(R.string.watch_hint_head_back)
    else -> stringResource(R.string.watch_hint_keep_walking)
}

@Composable
private fun StopButton(link: PhoneLink) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Button(
        onClick = {
            sending = true
            failed = false
            scope.launch {
                failed = !link.requestStop()
                sending = false
            }
        },
        enabled = !sending,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Magenta, contentColor = Body),
        label = {
            Text(stringResource(if (sending) R.string.watch_stopping else R.string.watch_stop_claim))
        },
    )
    if (failed) {
        Text(
            stringResource(R.string.watch_phone_unreachable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Closed(status: WatchStatus, link: PhoneLink) {
    Text(stringResource(R.string.watch_loop_closed), style = MaterialTheme.typography.titleMedium, color = Magenta)
    status.closedAreaSqMeters?.let {
        Text(LocalResources.current.formatArea(it), style = MaterialTheme.typography.displaySmall)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.watch_claim_on_phone),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    // OPEN, never START: the phone is waiting on this claim, and a start link
    // would begin a new walk on top of it.
    OpenOnPhoneButton(label = stringResource(R.string.watch_open_on_phone), uri = WatchLink.OPEN_URI, link = link)
}

@Composable
private fun OpenOnPhoneButton(label: String, uri: String, link: PhoneLink) {
    var result by remember { mutableStateOf<Boolean?>(null) }
    Button(
        onClick = {
            result = null
            link.openOnPhone(uri) { result = it }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
    )
    when (result) {
        true -> Text(stringResource(R.string.watch_check_phone), style = MaterialTheme.typography.bodySmall)
        false -> Text(
            stringResource(R.string.watch_phone_unreachable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        null -> Unit
    }
}

@Composable
private fun Message(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Violet)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}
