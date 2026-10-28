package io.app.enclose.tracking

/** How the user is moving, as far as the device can tell. */
enum class MotionActivity { WALKING, RUNNING, CYCLING, VEHICLE, STILL, UNKNOWN }

/**
 * What the user said they were setting out to do, chosen before starting a walk.
 *
 * This exists to *tighten* the speed rules, not to loosen them: someone who says
 * they're walking is held to a walking pace. Declaring [BIKE] only ever buys the
 * ceiling a ride needs, and never lifts [StretchJudge.ABSOLUTE_MAX_SPEED_MPS] —
 * so it can't be used to sneak a drive past.
 *
 * Ceilings are sustained speeds with generous headroom over real-world bests
 * (a 2-hour marathon is ~5.7 m/s), because a ceiling that real effort brushes
 * against turns honest ground into bridges. They were raised once already, when
 * a downhill stretch or a late fix could cost a whole outing; a stretch judged
 * too fast now costs only that stretch (see [StretchJudge]), and the ceilings
 * stayed where they were.
 */
enum class ActivityType(
    // No wording here: what each type is called lives in string resources,
    // picked per type in the UI — see io.app.enclose.ui.pick.
    val maxSpeedMps: Double,
    /**
     * Whether this mode can currently be chosen. Running and cycling are turned
     * off for now — the chips still show, greyed, so the app doesn't quietly
     * shrink and so turning them back on is this one flag rather than a
     * re-write. Everything else about them (ceilings, wording, the judge's
     * ability to *detect* a run or a ride and raise the ceiling accordingly)
     * stays exactly as it was.
     */
    val available: Boolean = true,
) {
    WALK(5.0), // ~18 km/h
    RUN(8.0, available = false), // ~29 km/h
    BIKE(11.0, available = false), // ~40 km/h
    ;

    /** A ride takes no steps, so steps can't be what vouches for one. */
    val onFoot: Boolean get() = this != BIKE

    companion object {
        /**
         * The stored preference, made safe to use: an unknown name (a rename, a
         * downgrade) and a mode that is no longer available both resolve to
         * [WALK] rather than leaving a walk declared as something the user can't
         * see or change.
         */
        fun resolve(storedName: String?): ActivityType =
            entries.firstOrNull { it.name == storedName && it.available } ?: WALK
    }
}

/**
 * A reading from the platform's activity classifier, normalised away from Play
 * Services types so everything that reads it stays plain Kotlin that can be unit
 * tested. [vehicleConfidence] is tracked separately from [activity] because a car
 * stopped at a light usually classifies as STILL while still reporting a high
 * in-vehicle confidence.
 *
 * All timestamps are monotonic (`SystemClock.elapsedRealtime`), never wall clock.
 */
data class MotionSample(
    val activity: MotionActivity = MotionActivity.UNKNOWN,
    /** Confidence (0-100) in [activity]. */
    val confidence: Int = 0,
    /** Confidence (0-100) that the user is in a vehicle, whatever [activity] says. */
    val vehicleConfidence: Int = 0,
    val atElapsedMs: Long = 0L,
)

/**
 * What the classifier said, confidently, when one fix was taken — the part of a
 * [MotionSample] a stretch is judged on, small enough to store with each point.
 *
 * Only readings that can change a verdict are kept. A confident WALKING raises
 * no ceiling a declared walk doesn't already have, and STILL says nothing about
 * how the ground was covered, so both are recorded as no evidence at all.
 */
enum class MotionEvidence {
    /** Confidently in a vehicle. Never lowers a ceiling; it can only stop one rising. */
    VEHICLE,

    /** Confidently running: earns a run's ceiling. */
    RUNNING,

    /** Confidently cycling: earns [StretchJudge.CONFIRMED_CYCLING_MAX_MPS]. */
    CYCLING,
    ;

    companion object {
        /** Play Services confidences are 0-100; 70 is a firm signal. */
        const val VEHICLE_CONFIDENCE = 70
        const val HUMAN_CONFIDENCE = 60

        /** Classifications older than this are ignored. */
        const val ACTIVITY_MAX_AGE_MS = 30_000L

        /**
         * The evidence [sample] gives about a fix taken at [atElapsedMs], or null.
         *
         * Stale readings are worse than none: a WALKING reading from five minutes
         * ago must not vouch for the drive happening now.
         */
        fun of(sample: MotionSample?, atElapsedMs: Long?): MotionEvidence? {
            if (sample == null || atElapsedMs == null) return null
            if (atElapsedMs - sample.atElapsedMs > ACTIVITY_MAX_AGE_MS) return null
            if (sample.vehicleConfidence >= VEHICLE_CONFIDENCE) return VEHICLE
            if (sample.confidence < HUMAN_CONFIDENCE) return null
            return when (sample.activity) {
                MotionActivity.RUNNING -> RUNNING
                MotionActivity.CYCLING -> CYCLING
                // A vehicle is judged on vehicleConfidence alone, above: a reading
                // under that bar is not firm enough to count against anyone.
                MotionActivity.VEHICLE,
                MotionActivity.WALKING,
                MotionActivity.STILL,
                MotionActivity.UNKNOWN,
                -> null
            }
        }
    }
}
