package io.app.enclose.ui

/**
 * Whether asking for split screen is worth doing on this device.
 *
 * There is no public API for an app to put itself into split screen.
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` is the closest thing, and AOSP documents it as
 * having no effect unless the device is *already* in split — which makes it
 * useless for getting there. Samsung's One UI is the one widespread
 * implementation that honours it from full screen, which is why the check below
 * is manufacturer-shaped rather than a capability query: there is no capability
 * to query.
 *
 * Getting this wrong is only ever a matter of politeness, never correctness. A
 * device wrongly judged capable makes a request that does nothing and then reads
 * the explanation a moment later; a device wrongly judged incapable gets the
 * explanation straight away. Both end at the same dialog — the check exists so
 * the common case doesn't sit through a pause waiting for an answer that was
 * never coming.
 *
 * Pure strings and booleans in, so the rule is unit tested rather than checked
 * on whichever phone happens to be plugged in.
 */
internal object SplitScreenSupport {

    /**
     * True when relaunching adjacent has a real chance of producing a split.
     *
     * @param alreadyMultiWindow the app is already sharing the screen — the one
     *   case AOSP does honour, though there is then nothing left to ask for.
     * @param manufacturer `Build.MANUFACTURER`.
     * @param hasSamsungMultiWindow whether the device declares Samsung's own
     *   multi-window system feature. Preferred over the name when present, since
     *   it survives rebadged and derived builds.
     */
    fun honoursAdjacentLaunch(
        alreadyMultiWindow: Boolean,
        manufacturer: String,
        hasSamsungMultiWindow: Boolean,
    ): Boolean = when {
        alreadyMultiWindow -> true
        hasSamsungMultiWindow -> true
        else -> manufacturer.equals(SAMSUNG, ignoreCase = true)
    }

    /** The Samsung system feature that tracks their own split-screen stack. */
    const val SAMSUNG_MULTIWINDOW_FEATURE = "com.sec.feature.multiwindow"

    private const val SAMSUNG = "samsung"
}
