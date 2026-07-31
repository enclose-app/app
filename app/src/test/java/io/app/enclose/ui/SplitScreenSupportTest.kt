package io.app.enclose.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the split-screen button asks the system or goes
 * straight to explaining itself. Wrong either way costs politeness, not
 * correctness — both paths end at the same dialog.
 */
class SplitScreenSupportTest {

    private fun honours(
        alreadyMultiWindow: Boolean = false,
        manufacturer: String = "Google",
        hasSamsungMultiWindow: Boolean = false,
    ) = SplitScreenSupport.honoursAdjacentLaunch(
        alreadyMultiWindow,
        manufacturer,
        hasSamsungMultiWindow,
    )

    @Test
    fun `stock Android is not worth asking`() {
        assertFalse(honours(manufacturer = "Google"))
    }

    @Test
    fun `Samsung is worth asking`() {
        assertTrue(honours(manufacturer = "samsung"))
    }

    /** Build.MANUFACTURER's casing is not something to bet a feature on. */
    @Test
    fun `the manufacturer name is matched whatever its casing`() {
        assertTrue(honours(manufacturer = "Samsung"))
        assertTrue(honours(manufacturer = "SAMSUNG"))
    }

    /**
     * The declared feature beats the name: rebadged and derived builds carry the
     * stack without necessarily carrying the manufacturer string.
     */
    @Test
    fun `the declared multi-window feature is enough on its own`() {
        assertTrue(honours(manufacturer = "Whoever", hasSamsungMultiWindow = true))
    }

    @Test
    fun `a device already in split honours it, as AOSP documents`() {
        assertTrue(honours(alreadyMultiWindow = true, manufacturer = "Google"))
    }
}
