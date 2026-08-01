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
        location: LocationReadiness = LocationReadiness.READY,
    ) = PanelSummary.of(walk, testMode, location)

    @Test
    fun `idle with permission offers to start`() {
        val summary = summarize()
        assertEquals(PanelStatus.IDLE, summary.status)
        assertEquals(PanelAction.START, summary.action)
    }

    @Test
    fun `no permission asks for it`() {
        val summary = summarize(location = LocationReadiness.DENIED)
        assertEquals(PanelStatus.NO_LOCATION, summary.status)
        assertEquals(PanelAction.GRANT_PERMISSION, summary.action)
    }

    @Test
    fun `a blocked permission sends the user to settings instead`() {
        val summary = summarize(location = LocationReadiness.BLOCKED)
        assertEquals(PanelStatus.NO_LOCATION, summary.status)
        assertEquals(PanelAction.OPEN_SETTINGS, summary.action)
    }

    /**
     * The one that started all this: permission granted, so the app happily began
     * a walk that could never record a metre, because every approximate fix is
     * past [io.app.enclose.tracking.TrackingManager.MAX_ACCURACY_METERS].
     */
    @Test
    fun `approximate-only location cannot start a walk`() {
        val summary = summarize(location = LocationReadiness.APPROXIMATE_ONLY)
        assertEquals(PanelStatus.NO_LOCATION, summary.status)
        // App settings, not the prompt: that is where the Precise toggle lives.
        assertEquals(PanelAction.OPEN_SETTINGS, summary.action)
    }

    /**
     * Permission is granted here; the device's own switch is off. Asking for
     * permission again would be a button that does nothing.
     */
    @Test
    fun `location switched off sends the user to location settings`() {
        val summary = summarize(location = LocationReadiness.SERVICES_OFF)
        assertEquals(PanelStatus.NO_LOCATION, summary.status)
        assertEquals(PanelAction.OPEN_LOCATION_SETTINGS, summary.action)
    }

    @Test
    fun `test mode needs no location permission`() {
        val summary = summarize(location = LocationReadiness.DENIED, testMode = true)
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
            location = LocationReadiness.DENIED,
        )
        assertEquals(PanelStatus.TRACKING, summary.status)
        assertEquals(PanelAction.END, summary.action)
    }

    /** Same rule for the device switch being turned off mid-walk. */
    @Test
    fun `location switched off mid-walk keeps the walk's own controls`() {
        val summary = summarize(
            walk = WalkState(isTracking = true),
            location = LocationReadiness.SERVICES_OFF,
        )
        assertEquals(PanelStatus.TRACKING, summary.status)
        assertEquals(PanelAction.END, summary.action)
    }
}
