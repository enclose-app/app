package io.app.enclose.ui

import io.app.enclose.tracking.TrackingManager

/**
 * Everything standing between the app and a recordable GPS fix, as one value.
 *
 * This was two booleans — granted, and blocked-from-asking — and between them
 * they could not express the two states that actually stop a walk from
 * recording. Both of those look identical from the panel: a walk that starts,
 * says "Walking", and never grows a path.
 *
 *  - **[APPROXIMATE_ONLY].** Android 12 put a Precise/Approximate toggle in the
 *    permission dialog itself, and "granted" was read as either. Approximate
 *    fixes land hundreds of metres out, which is past
 *    [TrackingManager.MAX_ACCURACY_METERS] — so every fix was discarded before it
 *    could anchor the path. The permission was granted, the app was recording,
 *    and nothing could ever be recorded.
 *  - **[SERVICES_OFF].** With the device's location master switch off, the
 *    request to subscribe *succeeds* and then simply never delivers a callback.
 *    Nothing throws and nothing reports, so the app had no idea.
 *
 * Making it one value means the panel, the Start button and the map all read the
 * same answer, and a new way to be un-ready has one place to be handled.
 */
// Public, unlike the other pure policies in this package: this one is a
// parameter of MapScreen, so it crosses from MainActivity rather than staying
// inside the panel.
enum class LocationReadiness {
    /** Precise location granted and the device's location switch is on. */
    READY,

    /** Precise location granted, but location is switched off device-wide. */
    SERVICES_OFF,

    /** Granted as "Approximate", which is too vague for anything to be recorded. */
    APPROXIMATE_ONLY,

    /** Not granted, and the system prompt will still appear. */
    DENIED,

    /** Not granted, and asking again would be a button that does nothing. */
    BLOCKED,
    ;

    /** True only when a walk started now could actually record something. */
    val canRecord: Boolean get() = this == READY

    /**
     * True when the map may switch its location component on. Any grant will do:
     * a vague blue dot is still worth drawing, even where it is far too vague to
     * claim ground with.
     */
    val hasPermission: Boolean
        get() = this == READY || this == SERVICES_OFF || this == APPROXIMATE_ONLY

    companion object {
        /**
         * @param precise `ACCESS_FINE_LOCATION` is granted.
         * @param approximate `ACCESS_COARSE_LOCATION` is granted (Android grants
         *   it alongside precise, so it is only interesting on its own).
         * @param servicesEnabled the device's location master switch.
         * @param promptBlocked the system will no longer show the permission
         *   dialog, so the only route left is app settings.
         */
        fun of(
            precise: Boolean,
            approximate: Boolean,
            servicesEnabled: Boolean,
            promptBlocked: Boolean,
        ): LocationReadiness = when {
            // The device switch is checked first among the granted cases: with it
            // off, precise permission buys nothing at all.
            precise && !servicesEnabled -> SERVICES_OFF
            precise -> READY
            // Reported ahead of a plain denial because the recovery differs. The
            // prompt cannot upgrade approximate to precise on every OS version,
            // so the honest instruction is the app's settings page.
            approximate -> APPROXIMATE_ONLY
            promptBlocked -> BLOCKED
            else -> DENIED
        }
    }
}
