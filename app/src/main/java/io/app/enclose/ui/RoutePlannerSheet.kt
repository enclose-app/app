package io.app.enclose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.app.enclose.data.RouteOrigin
import io.app.enclose.data.RouteSuggester
import io.app.enclose.data.RouteSuggestion
import io.app.enclose.data.RouteUnavailable
import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng
import io.app.enclose.ui.theme.LocalEncloseAccents
import kotlin.math.roundToInt

/**
 * "Give me a walk of about this far, from here."
 *
 * The whole feature in one sheet: a distance, a suggestion, and two ways to
 * answer it — take this one, or show me another. Three things about the shape of
 * it are deliberate:
 *
 *  - **Distance first, and remembered.** The number is the only thing the user
 *    is being asked for, so it is the biggest thing on the sheet and it survives
 *    between sessions ([io.app.enclose.data.UserSettings.plannedDistanceMeters]).
 *  - **Shuffle sits beside Start, not instead of it.** Turning a suggestion down
 *    is expected — that is what the user asked for — so "Another one" is a peer
 *    of the accept button rather than something to go back for.
 *  - **Every failure says which failure it is.** No fix, no network, nowhere to
 *    walk and nothing of the right length need four different things from the
 *    person reading them, and "couldn't find a route" for all four would send
 *    them looking in the wrong place three times out of four.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutePlannerSheet(
    plan: RoutePlan,
    targetMeters: Double,
    /** A route already accepted and being followed, or empty. */
    following: List<LatLng>,
    onTargetChange: (Double) -> Unit,
    onSuggest: () -> Unit,
    onShuffle: () -> Unit,
    onAccept: () -> Unit,
    /** Takes whatever is drawn off the map — a suggestion or an accepted route. */
    onClearRoute: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * How much of the screen this sheet is covering, so the map can frame the
     * route in what's left rather than under it.
     */
    onHeightChanged: (Int) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onHeightChanged(it.height) }
                // Scrollable, because the sheet's height is not allowed to be a
                // surprise: content that grows past what the sheet can show
                // re-anchors it mid-gesture and it settles to hidden, which is
                // the first suggestion dismissing the sheet that asked for it.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            // Anything drawn on the map — a suggestion being looked at, or a
            // route already taken — can be taken off it from here.
            val hasRoute = following.isNotEmpty() || plan is RoutePlan.Suggested
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        tint = LocalEncloseAccents.current.route,
                    )
                    Text("Plan a walk", style = MaterialTheme.typography.titleLarge)
                }
                // In the header rather than beside the route it removes: this is
                // how you say "no thanks" to a suggestion you are still looking
                // at, and the previous version only offered it for a route
                // already accepted — so the one state you most want out of had
                // no way out but a long-press nobody would guess at.
                if (hasRoute) {
                    TextButton(onClick = onClearRoute) { Text("Clear") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "A loop from where you are, on streets and paths — never motorways " +
                    "or trunk roads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Only when it is the route on the map: while a suggestion is being
            // previewed the map is showing *that*, and a line describing a
            // different route would be pointing at something invisible.
            if (following.isNotEmpty() && plan !is RoutePlan.Suggested) {
                Spacer(Modifier.height(14.dp))
                FollowingRow(following = following)
            }

            Spacer(Modifier.height(18.dp))
            DistancePicker(targetMeters = targetMeters, onTargetChange = onTargetChange)

            Spacer(Modifier.height(18.dp))
            // One slot, one height. The four states differ by a couple of
            // hundred pixels otherwise, and a sheet that jumps as the answer
            // arrives moves the buttons out from under the thumb that is on its
            // way to press one.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RESULT_MIN_HEIGHT),
                contentAlignment = Alignment.CenterStart,
            ) {
                when (plan) {
                    RoutePlan.Idle -> Unit
                    RoutePlan.Searching -> SearchingRow()
                    is RoutePlan.Suggested -> SuggestionCard(plan.suggestion)
                    is RoutePlan.Unavailable -> UnavailableCard(plan.reason)
                }
            }

            Spacer(Modifier.height(18.dp))
            Actions(
                plan = plan,
                onSuggest = onSuggest,
                onShuffle = onShuffle,
                onAccept = onAccept,
            )
        }
    }
}

/** The distance being asked for: steppers for precision, a slider for speed. */
@Composable
private fun DistancePicker(targetMeters: Double, onTargetChange: (Double) -> Unit) {
    val km = targetMeters / 1000.0
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = { onTargetChange(targetMeters - STEP_METERS) },
            enabled = targetMeters > RouteSuggester.MIN_TARGET_METERS,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Half a kilometre shorter")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                String.format(java.util.Locale.US, "%.1f km", km),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                // Rounded to five minutes: a pace estimate that reads to the
                // minute claims an accuracy no route planner has.
                "about ${walkingMinutes(targetMeters)} min at walking pace",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onTargetChange(targetMeters + STEP_METERS) },
            enabled = targetMeters < RouteSuggester.MAX_TARGET_METERS,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Half a kilometre longer")
        }
    }
    Slider(
        value = targetMeters.toFloat(),
        onValueChange = { onTargetChange(it.toDouble()) },
        valueRange = RouteSuggester.MIN_TARGET_METERS.toFloat()..
            RouteSuggester.MAX_TARGET_METERS.toFloat(),
        // One step per half kilometre, so the slider lands on the same values
        // the buttons produce rather than on 5.03 km.
        steps = ((RouteSuggester.MAX_TARGET_METERS - RouteSuggester.MIN_TARGET_METERS) /
            STEP_METERS).toInt() - 1,
    )
}

@Composable
private fun SearchingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            "Looking for a loop…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * What was found, in the terms someone deciding whether to walk it needs.
 *
 * The route is already drawn on the map behind this, which is the part that
 * actually answers "do I want to walk that?" — a distance and a sentence never
 * will. So the card says where to look, and how to get the sheet out of the way
 * to do it.
 */
@Composable
private fun SuggestionCard(suggestion: RouteSuggestion) {
    PlannerCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                tint = LocalEncloseAccents.current.route,
            )
            Column {
                Text(
                    formatDistance(suggestion.lengthMeters),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    describe(suggestion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "It's on the map above. Swipe this down to look at it properly — the " +
                "route stays until you take it or clear it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One line saying where this route came from.
 *
 * A previously walked loop says so plainly, because that is the strongest thing
 * that can be said about a route: it worked. A planned one reports how much of
 * it runs over ground already claimed, which is the difference between "your
 * usual loop with a new corner" and "somewhere you've never been".
 */
private fun describe(suggestion: RouteSuggestion): String = buildString {
    when (suggestion.origin) {
        RouteOrigin.WALKED_BEFORE -> {
            append("You've walked this one")
            suggestion.walkedAtEpochMs?.let { append(" · ${formatDate(it)}") }
        }
        RouteOrigin.PLANNED -> {
            val percent = (suggestion.familiarFraction * 100).roundToInt()
            append(
                when {
                    percent >= 60 -> "Mostly along ground you've already claimed"
                    percent >= 20 -> "$percent% along ground you've already claimed"
                    else -> "New ground"
                },
            )
        }
    }
    if (suggestion.startsAwayMeters >= JOIN_METERS) {
        append(" · starts ${formatDistance(suggestion.startsAwayMeters)} away")
    }
}

/** Why there is no route, and what to do about it. */
@Composable
private fun UnavailableCard(reason: RouteUnavailable) {
    PlannerCard {
        Text(
            when (reason) {
                RouteUnavailable.NO_FIX ->
                    "There's no position yet, and a suggested walk starts from where " +
                        "you're standing. Wait for your position to show on the map, " +
                        "then try again."
                RouteUnavailable.OFFLINE ->
                    "Route suggestions need a connection — the roads to plan along " +
                        "come from the map. It's the only part of Enclose that does: " +
                        "walking, claiming and everything you've already walked keep " +
                        "working offline."
                RouteUnavailable.NO_DATA ->
                    "Couldn't fetch the map data to plan with. This is the only part " +
                        "of Enclose that needs a connection — everything else works " +
                        "offline."
                RouteUnavailable.NO_PATHS_NEARBY ->
                    "There's no mapped road or path near you, so there's nothing to " +
                        "plan a route along. Walking anywhere still records normally."
                RouteUnavailable.NO_LOOP ->
                    "No loop of about that length around here. Try another distance, " +
                        "or press for another route — the next one sets off in a " +
                        "different direction."
                RouteUnavailable.OUT_OF_RANGE ->
                    "That's outside what one suggestion covers. Pick between " +
                        "${formatDistance(RouteSuggester.MIN_TARGET_METERS)} and " +
                        "${formatDistance(RouteSuggester.MAX_TARGET_METERS)}."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The route already accepted — the one currently drawn on the map. */
@Composable
private fun FollowingRow(following: List<LatLng>) {
    PlannerCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = LocalEncloseAccents.current.route,
            )
            Text(
                "Following a ${formatDistance(Geo.pathLengthMeters(following))} route",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Suggest, shuffle, and take it.
 *
 * The accept button is the widest thing on the row and says what happens next —
 * it starts the walk, so "Start this walk" is the literal truth rather than
 * "OK".
 */
@Composable
private fun Actions(
    plan: RoutePlan,
    onSuggest: () -> Unit,
    onShuffle: () -> Unit,
    onAccept: () -> Unit,
) {
    val searching = plan is RoutePlan.Searching
    when (plan) {
        is RoutePlan.Suggested -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onShuffle) {
                ButtonContent(Icons.Filled.Casino, "Another one")
            }
            Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                ButtonContent(Icons.Filled.PlayArrow, "Start this walk")
            }
        }

        else -> Button(
            onClick = if (plan is RoutePlan.Unavailable) onShuffle else onSuggest,
            enabled = !searching,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonContent(
                if (plan is RoutePlan.Unavailable) Icons.Filled.Refresh else Icons.Filled.Route,
                if (plan is RoutePlan.Unavailable) "Try again" else "Suggest a route",
            )
        }
    }
}

/**
 * An untitled card. [SectionCard] is the app's grouped block and takes a title;
 * everything on this sheet is one statement about one route, and a heading over
 * a single sentence would be furniture.
 */
@Composable
private fun PlannerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) },
    )
}

/**
 * Room kept for whatever the planner has to say, so the sheet is the same height
 * before and after it says it. Sized to the tallest of the four — the suggestion
 * card with its "look at it on the map" line.
 */
private val RESULT_MIN_HEIGHT = 132.dp

/** Steps the distance moves in — half a kilometre, on the buttons and the slider. */
private const val STEP_METERS = 500.0

/**
 * Beyond this the walk to the start of a suggested route is worth mentioning.
 * Under it, it's the width of a street and saying so is noise.
 */
private const val JOIN_METERS = 60.0

/** 5 km/h, the standard flat-ground walking pace, rounded to five minutes. */
private fun walkingMinutes(meters: Double): Int {
    val minutes = meters / 1000.0 * 12.0
    return ((minutes / 5).roundToInt() * 5).coerceAtLeast(5)
}
