package io.app.enclose.tracking

/** How the user is moving, as far as the device can tell. */
enum class MotionActivity { WALKING, RUNNING, CYCLING, VEHICLE, STILL, UNKNOWN }

/**
 * What the user said they were setting out to do, chosen before starting a walk.
 *
 * This exists to *tighten* the speed rules, not to loosen them: someone who says
 * they're walking is held to a walking pace, which catches slow city driving that
 * a cycling-friendly ceiling would let through. Declaring [BIKE] only ever buys
 * the same ceiling the gate used before, and never exempts anyone from the
 * in-vehicle check or [MotionGate.ABSOLUTE_MAX_SPEED_MPS] — so it can't be used
 * to sneak a drive past.
 *
 * Ceilings are sustained averages with generous headroom over real-world bests
 * (a 2-hour marathon is ~5.7 m/s), because being wrongly stopped mid-effort is
 * far worse than a slightly permissive limit that the other signals still cover.
 */
enum class ActivityType(
    /** Chip label: "Walk", "Run", "Bike". */
    val label: String,
    /** Present-tense wording for the live panel: "Walking", "Cycling"… */
    val activeLabel: String,
    /** The trip as a noun: "Start a **ride**", "too fast for a **run**". */
    val noun: String,
    val maxSpeedMps: Double,
) {
    WALK("Walk", "Walking", "walk", 4.0), // ~14 km/h
    RUN("Run", "Running", "run", 7.0), // ~25 km/h
    BIKE("Bike", "Cycling", "ride", 9.0), // ~32 km/h
}

/**
 * A reading from the platform's activity classifier, normalised away from Play
 * Services types so [MotionGate] stays a plain Kotlin object that can be unit
 * tested. [vehicleConfidence] is tracked separately from [activity] because a
 * car stopped at a light usually classifies as STILL while still reporting a
 * high in-vehicle confidence.
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

/** Why movement is being rejected while a walk is in progress. */
enum class BlockReason {
    /** The activity classifier says this is a vehicle. */
    VEHICLE,

    /** Sustained speed no human-powered trip plausibly reaches. */
    TOO_FAST,
}

/** Why a walk was thrown away. Drives the explanation the user sees. */
enum class VoidReason {
    /** Vehicle movement continued past the grace window. */
    VEHICLE,

    /** Implausible speed continued past the grace window. */
    TOO_FAST,

    /**
     * Recording resumed too far from where it was suspended. Ground covered
     * while blocked was never recorded, so the path can't be trusted to
     * describe where the user actually went.
     */
    UNVERIFIED_GAP,
    ;

    companion object {
        fun from(reason: BlockReason): VoidReason = when (reason) {
            BlockReason.VEHICLE -> VEHICLE
            BlockReason.TOO_FAST -> TOO_FAST
        }
    }
}

/**
 * Decides whether the movement being recorded is human-powered.
 *
 * Enclose only counts ground you covered on foot or by bike, so a drive must not
 * be able to enclose a territory. Two independent signals are combined:
 *
 *  - the platform activity classifier (walking / running / cycling / in-vehicle),
 *    which catches slow city driving that speed alone can't distinguish, and
 *  - sustained speed, which needs no permission and works on any device, and so
 *    remains the backstop when the classifier is unavailable or denied.
 *
 * Speed is averaged over a short window ([SPEED_WINDOW] samples) so a single GPS
 * spike can't block a genuine walk. The ceiling comes from the [ActivityType] the
 * user declared, which a confident classification may raise (a walker who got on
 * a bike) but never lower — and nothing raises it past
 * [ABSOLUTE_MAX_SPEED_MPS], beyond which no movement is treated as human-powered
 * regardless of what was declared or detected.
 *
 * Blocking is not immediately fatal: the caller drops the fix and shows a
 * warning, and only once movement stays blocked for [GRACE_MS] does the gate
 * return [Verdict.Void]. That tolerates a brief bus hop or a bad fix without
 * throwing away a long walk, while making a real drive unable to produce a claim.
 *
 * Instances are stateful (speed window, how long we've been blocked) and belong
 * to a single walk — call [reset] when one starts.
 */
class MotionGate {

    sealed interface Verdict {
        /** Movement looks human-powered; record the fix. */
        data object Allowed : Verdict

        /**
         * Movement is not human-powered: drop the fix and warn.
         * [sinceElapsedMs] is when blocking began, for the warning's countdown.
         */
        data class Blocked(val reason: BlockReason, val sinceElapsedMs: Long) : Verdict

        /** Blocked for longer than [GRACE_MS]: the walk can no longer be trusted. */
        data class Void(val reason: BlockReason) : Verdict
    }

    private val speeds = ArrayDeque<Double>()
    private var blockedSinceElapsedMs: Long? = null
    private var declared: ActivityType = ActivityType.WALK

    /** Forget the previous walk and adopt the activity the user chose for this one. */
    fun reset(activityType: ActivityType = ActivityType.WALK) {
        speeds.clear()
        blockedSinceElapsedMs = null
        declared = activityType
    }

    /**
     * Judge the movement at [nowElapsedMs].
     *
     * @param speedMps best available speed for this moment — the larger of the
     *   fix's own reported speed and the speed implied by the distance since the
     *   previous fix. Null when unknown (it simply doesn't feed the window).
     * @param motion latest activity classification, or null when unavailable
     *   (permission denied, no Play Services) — the gate then runs on speed alone.
     */
    fun evaluate(nowElapsedMs: Long, speedMps: Double?, motion: MotionSample?): Verdict {
        if (speedMps != null && speedMps.isFinite() && speedMps >= 0.0) {
            speeds.addLast(speedMps)
            while (speeds.size > SPEED_WINDOW) speeds.removeFirst()
        }
        val sustained = if (speeds.isEmpty()) null else speeds.average()

        // Stale classifications are worse than none: a WALKING reading from five
        // minutes ago must not whitelist the drive happening now.
        val fresh = motion != null && (nowElapsedMs - motion.atElapsedMs) <= ACTIVITY_MAX_AGE_MS
        val inVehicle = fresh && motion!!.vehicleConfidence >= VEHICLE_CONFIDENCE
        val detected = if (fresh && motion!!.confidence >= HUMAN_CONFIDENCE) {
            ceilingFor(motion.activity)
        } else {
            null
        }
        // The declared activity sets the ceiling, but a confident classification
        // can raise it: someone who set out to walk and ended up on a bike gets
        // the bike's limit rather than a voided ride.
        val ceiling = maxOf(declared.maxSpeedMps, detected ?: 0.0)

        val reason = when {
            inVehicle -> BlockReason.VEHICLE
            sustained == null -> null
            // Nothing — declared or detected — justifies this speed.
            sustained > ABSOLUTE_MAX_SPEED_MPS -> BlockReason.TOO_FAST
            sustained > ceiling -> BlockReason.TOO_FAST
            else -> null
        }

        if (reason == null) {
            blockedSinceElapsedMs = null
            return Verdict.Allowed
        }

        val since = blockedSinceElapsedMs ?: nowElapsedMs.also { blockedSinceElapsedMs = it }
        return if (nowElapsedMs - since >= GRACE_MS) {
            Verdict.Void(reason)
        } else {
            Verdict.Blocked(reason, since)
        }
    }

    /**
     * The ceiling a detected activity justifies, or null if it justifies none.
     *
     * A confident cycling reading earns more headroom than merely *declaring*
     * BIKE does ([CONFIRMED_CYCLING_MAX_MPS] vs [ActivityType.BIKE]): declaring
     * is a claim, whereas the classifier corroborating it is evidence — and fast
     * descents and e-bikes really do sustain more than 32 km/h. Wrongly voiding
     * an hour-long ride is a far worse outcome than a slightly high ceiling that
     * the in-vehicle check still covers.
     */
    private fun ceilingFor(activity: MotionActivity): Double? = when (activity) {
        MotionActivity.WALKING -> ActivityType.WALK.maxSpeedMps
        MotionActivity.RUNNING -> ActivityType.RUN.maxSpeedMps
        MotionActivity.CYCLING -> CONFIRMED_CYCLING_MAX_MPS
        MotionActivity.VEHICLE, MotionActivity.STILL, MotionActivity.UNKNOWN -> null
    }

    companion object {
        /**
         * Ceiling once the classifier has confirmed cycling: 14 m/s ≈ 50 km/h,
         * which covers descents and e-bikes.
         */
        const val CONFIRMED_CYCLING_MAX_MPS = 14.0

        /** 20 m/s ≈ 72 km/h — nothing human-powered sustains this. */
        const val ABSOLUTE_MAX_SPEED_MPS = 20.0

        /** Play Services confidences are 0-100; 70 is a firm signal. */
        const val VEHICLE_CONFIDENCE = 70
        const val HUMAN_CONFIDENCE = 60

        /** Classifications older than this are ignored. */
        const val ACTIVITY_MAX_AGE_MS = 30_000L

        /** How long blocked movement is tolerated before the walk is voided. */
        const val GRACE_MS = 30_000L

        /** Speed samples averaged together (≈15 s at a 3 s fix interval). */
        const val SPEED_WINDOW = 5
    }
}
