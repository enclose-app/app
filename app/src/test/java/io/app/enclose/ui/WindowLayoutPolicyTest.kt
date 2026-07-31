package io.app.enclose.ui

import io.app.enclose.ui.WindowLayoutPolicy.COMPACT_HEIGHT_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split screen is the case these thresholds exist for, so the boundary is pinned
 * here rather than checked by resizing a window by hand.
 */
class WindowLayoutPolicyTest {

    @Test
    fun `a full-height phone window leaves the panel as the user set it`() {
        assertFalse(WindowLayoutPolicy.collapsePanel(userCollapsed = false, heightDp = 800))
        assertTrue(WindowLayoutPolicy.collapsePanel(userCollapsed = true, heightDp = 800))
    }

    @Test
    fun `a split-screen half folds the panel whatever the user chose`() {
        assertTrue(WindowLayoutPolicy.collapsePanel(userCollapsed = false, heightDp = 400))
    }

    @Test
    fun `the threshold itself counts as roomy`() {
        assertFalse(
            WindowLayoutPolicy.collapsePanel(userCollapsed = false, heightDp = COMPACT_HEIGHT_DP),
        )
        assertTrue(
            WindowLayoutPolicy.collapsePanel(
                userCollapsed = false,
                heightDp = COMPACT_HEIGHT_DP - 1,
            ),
        )
    }

    /**
     * Growing the window back must not overrule a standing preference — the user
     * minimised the panel to see the map, not to survive one small window.
     */
    @Test
    fun `a roomy window never forces the panel open`() {
        assertTrue(WindowLayoutPolicy.collapsePanel(userCollapsed = true, heightDp = 2000))
    }

    @Test
    fun `the fold control is hidden only where folding is already forced`() {
        assertTrue(WindowLayoutPolicy.panelFoldable(heightDp = 800))
        assertFalse(WindowLayoutPolicy.panelFoldable(heightDp = 400))
    }

    // --- where the controls go ------------------------------------------------

    /** The rails' own order, top to bottom. */
    private val allControls = listOf(
        MapControl.FLOAT,
        MapControl.SPLIT,
        MapControl.ZOOM_IN,
        MapControl.ZOOM_OUT,
        MapControl.HOME,
        MapControl.RECENTER,
        MapControl.BASEMAP,
    )

    @Test
    fun `a tall window keeps every control on the right rail`() {
        val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = 600)
        assertEquals(allControls, layout.right)
        assertTrue(layout.left.isEmpty())
        assertTrue(layout.menu.isEmpty())
    }

    /**
     * The first thing given up is the right rail's monopoly, not a control:
     * zoom crosses to the empty left edge, where it is still one press away.
     */
    @Test
    fun `a short window moves zoom to the left rather than hiding anything`() {
        // Five slots: seven controls don't fit, five do once zoom moves.
        val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = 290)
        assertEquals(listOf(MapControl.ZOOM_IN, MapControl.ZOOM_OUT), layout.left)
        assertTrue("nothing needs the menu yet", layout.menu.isEmpty())
        assertEquals(
            listOf(
                MapControl.FLOAT,
                MapControl.SPLIT,
                MapControl.HOME,
                MapControl.RECENTER,
                MapControl.BASEMAP,
            ),
            layout.right,
        )
    }

    /**
     * Only once both edges are full does anything leave the screen — and what
     * leaves is what a walker doesn't reach for mid-stride.
     */
    @Test
    fun `only the controls that still don't fit go to the menu`() {
        // Three slots on the right, after zoom has already moved left.
        val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = 170)
        assertEquals(listOf(MapControl.ZOOM_IN, MapControl.ZOOM_OUT), layout.left)
        assertEquals(listOf(MapControl.HOME, MapControl.RECENTER, MapControl.BASEMAP), layout.right)
        assertEquals(listOf(MapControl.FLOAT, MapControl.SPLIT), layout.menu)
    }

    /**
     * Growing the window must put a control back where it was rather than
     * reshuffling the stack under the user's thumb.
     */
    @Test
    fun `every rail keeps the drawing order, not the priority order`() {
        val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = 170)
        assertEquals(layout.right, allControls.filter { it in layout.right })
        assertEquals(layout.left, allControls.filter { it in layout.left })
        assertEquals(layout.menu, allControls.filter { it in layout.menu })
    }

    @Test
    fun `every control is placed exactly once`() {
        listOf(0, 100, 170, 290, 400, 2000).forEach { height ->
            val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = height)
            val placed = layout.right + layout.left + layout.menu
            assertEquals("at ${height}dp", allControls.size, placed.size)
            assertEquals("at ${height}dp", allControls.toSet(), placed.toSet())
        }
    }

    /**
     * A window with no room at all still shows one control on the right rather
     * than an empty strip: something to press beats a tidy nothing.
     */
    @Test
    fun `an impossibly short window still keeps one control on the rail`() {
        val layout = WindowLayoutPolicy.placeControls(allControls, railHeightDp = 0)
        assertEquals(listOf(MapControl.RECENTER), layout.right)
    }

    @Test
    fun `a control that isn't offered is never invented`() {
        // Floating and split are absent on devices that can't do either.
        val offered = allControls - MapControl.FLOAT - MapControl.SPLIT
        val layout = WindowLayoutPolicy.placeControls(offered, railHeightDp = 600)
        assertEquals(offered, layout.right)
    }
}
