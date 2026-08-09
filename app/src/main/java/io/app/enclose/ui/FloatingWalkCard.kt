package io.app.enclose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.app.enclose.data.Territory
import io.app.enclose.geo.LatLng
import io.app.enclose.tracking.TrackingManager
import io.app.enclose.ui.theme.LocalEncloseAccents
import kotlinx.coroutines.delay

/**
 * What the app is while it's floating over another one: the map, centred on
 * where you are, with the walk drawn on it and one line of figures over the top.
 *
 * A picture-in-picture window takes no touches — anything tapped goes to the
 * system, not to us — so this is a read-out, not a control panel. Tapping the
 * window returns to the full app, which is where every action lives.
 *
 * Two things it deliberately does *not* do:
 *  - **Save the camera.** `onCameraIdle` is left alone, so following the walker
 *    around a tiny window can't overwrite the framing the user set up on the real
 *    map and expects to find when they come back.
 *  - **Cover the bottom-left corner.** That's MapLibre's logo and the
 *    OpenStreetMap attribution, which is a licence requirement rather than
 *    decoration, so the figures ride at the top.
 *
 * The status wording comes from the same [PanelSummary] the bottom panel uses,
 * so the two can't describe one walk differently.
 */
@Composable
fun FloatingWalkCard(
    walk: TrackingManager.WalkState,
    territories: List<Territory>,
    hasLocationPermission: Boolean,
    basemap: BasemapStyle,
    /** The suggested route being followed, drawn faintly under the trail. */
    plannedRoute: List<LatLng> = emptyList(),
) {
    val accents = LocalEncloseAccents.current
    val controller = rememberMapController()
    // Location readiness doesn't change what a read-out says: what matters is
    // whether a walk is running, which the state itself carries. This card takes
    // no touches either, so the recovery buttons the other statuses lead to
    // couldn't be pressed from here anyway.
    val summary = PanelSummary.of(
        walk = walk,
        testMode = false,
        location = LocationReadiness.READY,
    )

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(walk.isTracking) {
        while (walk.isTracking) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = walk.startedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L

    val (dot, label) = when (summary.status) {
        PanelStatus.READY -> accents.success to "Ready to claim"
        PanelStatus.BLOCKED -> MaterialTheme.colorScheme.error to "Paused"
        PanelStatus.TRACKING -> accents.trail to walk.activityType.activeLabel
        PanelStatus.IDLE, PanelStatus.NO_LOCATION ->
            MaterialTheme.colorScheme.onSurfaceVariant to "No walk in progress"
    }

    Box(Modifier.fillMaxSize()) {
        EncloseMap(
            walk = walk,
            territories = territories,
            hasLocationPermission = hasLocationPermission,
            controller = controller,
            basemap = basemap,
            plannedRoute = plannedRoute,
            // No initial camera on purpose: with none saved, the map flies to
            // the user as soon as it has a fix, which is the only framing a
            // window this size is any use at.
            initialCamera = null,
            modifier = Modifier.fillMaxSize(),
        )

        // Follow the walker, walk or no walk. On the full map following is
        // switched off by a pan, because the user is looking at something; a
        // window this size takes no gestures at all, so there is nothing to
        // switch it off and no reason to.
        LaunchedEffect(controller.isStyleLoaded) { controller.followUser = true }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapSurface {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dot),
                    )
                    Text(
                        if (summary.status.isTracking) {
                            "${formatDistance(walk.distanceMeters)} · " +
                                formatElapsed(elapsedMs)
                        } else {
                            label
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
