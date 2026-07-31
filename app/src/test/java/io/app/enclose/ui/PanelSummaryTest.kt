package io.app.enclose.ui

import io.app.enclose.tracking.BlockReason
import io.app.enclose.tracking.TrackingManager.WalkState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panel, the collapsed panel and the floating card all read this, so a wrong
 * answer here shows up as three surfaces disagreeing about the same walk.
 */
class PanelSummaryTest {

    private fun summarize(
        walk: WalkState = WalkState(),
        testMode: Boolean = false,
        hasLocationPermission: Boolean = true,
        permissionBlocked: Boolean = false,
    ) = PanelSummary.of(walk, testMode, hasLocationPermission, permissionBlocked)

    @Test
    fun `idle with permission offers to start`() {
        val summary = summarize()
        assertEquals(PanelStatus.IDLE, summary.status)
        assertEquals(PanelAction.START, summary.action)
    }

    @Test
    fun `no permission asks for it`() {
        val summary = summarize(hasLocationPermission = false)
        assertEquals(PanelStatus.NO_PERMISSION, summary.status)
        assertEquals(PanelAction.GRANT_PERMISSION, summary.action)
    }

    @Test
    fun `a blocked permission sends the user to settings instead`() {
        val summary = summarize(hasLocationPermission = false, permissionBlocked = true)
        assertEquals(PanelStatus.NO_PERMISSION, summary.status)
        assertEquals(PanelAction.OPEN_SETTINGS, summary.action)
    }

    @Test
    fun `test mode needs no location permission`() {
        val summary = summarize(hasLocationPermission = false, testMode = true)
        assertEquals(PanelStatus.IDLE, summary.status)
        assertEquals(PanelAction.START, summary.action)
    }

    @Test
    fun `a walk in progress ends rather than claims`() {
        val summary = summarize(WalkState(isTracking = true))
        assertEquals(PanelStatus.TRACKING, summary.status)
        assertEquals(PanelAction.END, summary.action)
    }

    @Test
    fun `a closable loop leads with the claim`() {
        val summary = summarize(WalkState(isTracking = true, readyToClose = true))
        assertEquals(PanelStatus.READY, summary.status)
        assertEquals(PanelAction.CLAIM, summary.action)
    }

    @Test
    fun `blocked movement outranks everything else about the walk`() {
        val summary = summarize(
            WalkState(
                isTracking = true,
                readyToClose = true,
                blockedReason = BlockReason.VEHICLE,
            ),
        )
        assertEquals(PanelStatus.BLOCKED, summary.status)
        assertEquals(PanelAction.END, summary.action)
    }

    /**
     * Permission can be revoked from system settings while the walk is running.
     * The controls for the walk you are on must not vanish — the way to keep it
     * is the Stop button that would go with them.
     */
    @Test
    fun `losing permission mid-walk keeps the walk's own controls`() {
        val summary = summarize(
            walk = WalkState(isTracking = true),
            hasLocationPermission = false,
        )
        assertEquals(PanelStatus.TRACKING, summary.status)
        assertEquals(PanelAction.END, summary.action)
    }
}
