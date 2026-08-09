package io.app.enclose.ui

/**
 * How much room the window has, and what the map screen should give up when it
 * runs short.
 *
 * Split screen halves the height of a phone window, and the bottom panel was
 * sized for a whole one: expanded, it and the top row between them leave a strip
 * of map too thin to see a loop in. Rather than a second layout, the screen
 * folds the panel it already has — see [collapsePanel] — so there is only ever
 * one panel to keep correct.
 *
 * Pure integers in, decisions out: no Android types, so the thresholds are unit
 * tested rather than eyeballed on one device.
 */
internal object WindowLayoutPolicy {

    /**
     * Below this the window is a split-screen half (or a small landscape phone).
     *
     * A phone in vertical split gets roughly 400 dp per half on a 900 dp screen,
     * and the expanded panel alone is ~260 dp of it. 480 dp keeps ordinary
     * portrait phones (600 dp+ of content height) on the full layout while
     * catching every split-screen half.
     */
    const val COMPACT_HEIGHT_DP = 480

    /**
     * Whether the panel must be folded regardless of what the user chose.
     *
     * Deliberately one-way: a short window forces the panel collapsed, but a
     * roomy one never forces it open — [UserSettings.panelCollapsed] is a
     * standing preference and the window growing back is no reason to overrule
     * it.
     */
    fun collapsePanel(userCollapsed: Boolean, heightDp: Int): Boolean =
        userCollapsed || heightDp < COMPACT_HEIGHT_DP

    /**
     * Whether the panel's collapse control should be offered at all. In a window
     * this short the panel is folded either way, so a chevron that expands it
     * back over the whole map is a control with nothing behind it.
     *
     * The top row of chips deliberately stays put at every size: the menu, the
     * profile and the claim list are only reachable from there, and a layout
     * that hides the way out of test mode is worse than one that's a little
     * tight.
     */
    fun panelFoldable(heightDp: Int): Boolean = heightDp >= COMPACT_HEIGHT_DP

    /**
     * Where each of the map's floating controls goes, given how much room is
     * actually left between the top row and the panel.
     *
     * The rail is a fixed-size stack — 48 dp targets are the accessibility
     * minimum and shrinking them to fit is not an option — so a window too short
     * for all of them spreads them out instead, in two steps:
     *
     *  1. **Zoom moves to the left edge.** The opposite side of the screen is
     *     empty at every window size, and zoom is a pair of buttons people press
     *     repeatedly while looking at the map. Burying those in a menu would be
     *     the worst possible trade; the horizontal room is free.
     *  2. **The left rail then takes the overflow as well**, lowest priority
     *     first. This step used to be missing, and its absence was visible on
     *     exactly the windows the whole policy exists for: a split-screen half
     *     put three controls in the ⋮ menu while the left edge held two zoom
     *     buttons and a hand's worth of empty space. A control on the far rail is
     *     still one press; a control in the menu is two and has to be found.
     *  3. **Only what neither rail can hold moves to the ⋮ menu**, lowest
     *     priority first ([RAIL_PRIORITY]) — recenter and zoom are what a walker
     *     reaches for mid-stride, while the window controls are pressed once and
     *     then not again.
     *
     * [railHeightDp] is the room the *right* rail has. The left is treated as
     * having the same: it starts higher up to clear the OpenStreetMap
     * attribution, and the right gives up a slot to the map's compass, which
     * comes to within a few dp of the same number.
     *
     * [available] is in the order the rails draw them, top to bottom, and each
     * result preserves that order: growing the window puts a control back exactly
     * where it was rather than reshuffling the stack under the user's thumb.
     */
    fun placeControls(available: List<MapControl>, railHeightDp: Int): ControlLayout {
        val fits = (railHeightDp / CONTROL_SLOT_DP).coerceAtLeast(1)
        if (available.size <= fits) return ControlLayout(right = available)

        // Step 1: zoom to the left edge, which frees two slots on the right.
        val crossed = available.filter { it in ZOOM_CONTROLS }.toMutableSet()
        var right = available.filterNot { it in crossed }

        // Step 2: keep crossing, lowest priority first, while the left rail has
        // room and the right one is still over-full.
        if (right.size > fits) {
            val room = (fits - crossed.size).coerceAtLeast(0)
            val overflow = right.size - fits
            available
                .filterNot { it in crossed }
                .sortedByDescending { RAIL_PRIORITY.indexOf(it) }
                .take(minOf(room, overflow))
                .forEach { crossed.add(it) }
            right = available.filterNot { it in crossed }
        }
        val left = available.filter { it in crossed }
        if (right.size <= fits) return ControlLayout(right = right, left = left)

        // Step 3: neither rail can hold the remainder.
        val kept = right.sortedBy { RAIL_PRIORITY.indexOf(it) }.take(fits).toSet()
        return ControlLayout(
            right = right.filter { it in kept },
            left = left,
            menu = right.filterNot { it in kept },
        )
    }

    /** A 48 dp touch target plus the 8 dp gap under it. */
    private const val CONTROL_SLOT_DP = 56

    /** Moved to the left edge first, together, because they're used as a pair. */
    private val ZOOM_CONTROLS = setOf(MapControl.ZOOM_IN, MapControl.ZOOM_OUT)

    /** Most worth keeping on the rail first. */
    private val RAIL_PRIORITY = listOf(
        MapControl.RECENTER,
        MapControl.ZOOM_IN,
        MapControl.ZOOM_OUT,
        // Above home and the basemap: planning a route is the thing somebody
        // opens this app to do before setting off, and it reads as a button
        // rather than as a menu item.
        MapControl.PLAN,
        MapControl.HOME,
        MapControl.BASEMAP,
        MapControl.FLOAT,
        MapControl.SPLIT,
    )
}

/**
 * Where the map's controls ended up. Each list keeps the rails' own top-to-bottom
 * order, and every control appears in exactly one of them.
 */
internal data class ControlLayout(
    val right: List<MapControl> = emptyList(),
    /** The left edge, used for zoom once the right rail runs out of room. */
    val left: List<MapControl> = emptyList(),
    /** Drawn as items in the ⋮ menu instead of as buttons. */
    val menu: List<MapControl> = emptyList(),
)

/**
 * The map's floating controls, as things that can be placed rather than as
 * composables — so where each one goes is decided by [WindowLayoutPolicy] and
 * can be unit tested, while what each one *does* stays in `MapScreen`.
 */
internal enum class MapControl {
    FLOAT,
    SPLIT,
    ZOOM_IN,
    ZOOM_OUT,
    HOME,
    RECENTER,
    BASEMAP,

    /** Opens the route planner — "suggest me a walk of this many kilometres". */
    PLAN,
}
