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
 * They were raised once already, in the same pass that introduced strikes: on a
 * long walk the old ceilings were being brushed by ordinary things — a downhill
 * stretch, a fix that arrived late and read as a sprint — and the cost of that
 * was the entire outing. The signals that actually catch a drive are the
 * in-vehicle classification and [MotionGate.ABSOLUTE_MAX_SPEED_MPS], and neither
 * of those was loosened by as much.
 */
enum class ActivityType(
    /** Chip label: "Walk", "Run", "Bike". */
    val label: String,
    /** Present-tense wording for the live panel: "Walking", "Cycling"… */
    val activeLabel: String,
    /** The trip as a noun: "Start a **ride**", "too fast for a **run**". */
    val noun: String,
    val maxSpeedMps: Double,
    /**
     * Whether this mode can currently be chosen. Running and cycling are turned
     * off for now — the chips still show, greyed, so the app doesn't quietly
     * shrink and so turning them back on is this one flag rather than a
     * re-write. Everything else about them (ceilings, wording, the gate's
     * ability to *detect* a run or a ride and raise the ceiling accordingly)
     * stays exactly as it was.
     */
    val available: Boolean = true,
) {
    WALK("Walk", "Walking", "walk", 5.0), // ~18 km/h
    RUN("Run", "Running", "run", 8.0, available = false), // ~29 km/h
    BIKE("Bike", "Cycling", "ride", 11.0, available = false), // ~40 km/h
    ;

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

/**
 * What went wrong with the movement — used both for a warning (a strike) and,
 * once the strikes run out, for the explanation of why the walk was thrown away.
 */
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
 * Blocking is not immediately fatal, and neither is running out of patience with
 * it. Movement blocked for longer than [GRACE_MS] costs the walk a **strike**
 * ([Verdict.Strike]) rather than the walk itself; only the [MAX_STRIKES]th one
 * returns [Verdict.Void].
 *
 * That is deliberately generous, because the two errors are not symmetrical. A
 * cheat who drives loses nothing by being caught on the third strike instead of
 * the first — they still can't claim. Someone two hours into a real walk who
 * takes one tram stop, or whose phone hands over a burst of bad fixes, loses
 * everything they walked for. The old single-strike rule made that second case
 * common enough to be the thing people complained about, and a claim can't be
 * re-walked from the couch. Three strikes of [GRACE_MS] each is roughly four and
 * a half minutes of sustained vehicle movement before a walk dies — far past a
 * bad patch of GPS, nowhere near a drive worth claiming.
 *
 * Instances are stateful (speed window, how long we've been blocked, strikes so
 * far) and belong to a single walk — call [reset] when one starts.
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

        /**
         * Blocked for longer than [GRACE_MS], with strikes left: a warning. The
         * walk carries on, the fix is still dropped, and the gate starts over —
         * so carrying on regardless costs another full grace window before the
         * next strike, rather than a strike per fix.
         *
         * [count] is how many strikes this walk has now used, out of
         * [MAX_STRIKES].
         */
        data class Strike(val reason: BlockReason, val count: Int) : Verdict

        /** The last strike is used up: the walk can no longer be trusted. */
        data class Void(val reason: BlockReason) : Verdict
    }

    private val speeds = ArrayDeque<Double>()
    private var blockedSinceElapsedMs: Long? = null
    private var declared: ActivityType = ActivityType.WALK

    /** Warnings this walk has used, across every reason. */
    var strikes: Int = 0
        private set

    /** Forget the previous walk and adopt the activity the user chose for this one. */
    fun reset(activityType: ActivityType = ActivityType.WALK) {
        speeds.clear()
        blockedSinceElapsedMs = null
        strikes = 0
        declared = activityType
    }

    /**
     * Spend a strike on something this gate didn't judge itself — recording
     * resuming a long way from where it was suspended, which is the caller's
     * check because only it knows where the path went.
     *
     * Shares the counter on purpose: three warnings means three warnings for the
     * walk, not three of each kind. Also clears the speed window and the grace
     * countdown, so whatever comes next is judged fresh.
     *
     * Returns the strike count, which the caller compares against [MAX_STRIKES].
     */
    fun bankStrike(): Int {
        strikes += 1
        clearSpeedWindow()
        return strikes
    }

    /**
     * Forget the speed history and the grace countdown, but not the strikes.
     *
     * For the caller's signal-gap handling: a burst of fixes after the device
     * dozed describes nothing, so it must not be averaged into a verdict — but
     * losing the signal is not an amnesty either. A walk already on its last
     * warning is still on its last warning when the fixes come back.
     */
    fun clearSpeedWindow() {
        speeds.clear()
        blockedSinceElapsedMs = null
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
        if (nowElapsedMs - since < GRACE_MS) return Verdict.Blocked(reason, since)

        // The grace window is spent. [bankStrike] clears the countdown, so the
        // next strike is another full window away rather than the very next fix.
        val count = bankStrike()
        return if (count >= MAX_STRIKES) Verdict.Void(reason) else Verdict.Strike(reason, count)
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
         * Ceiling once the classifier has confirmed cycling: 16 m/s ≈ 58 km/h,
         * which covers descents and e-bikes.
         */
        const val CONFIRMED_CYCLING_MAX_MPS = 16.0

        /**
         * 22 m/s ≈ 79 km/h — nothing human-powered *sustains* this over the
         * [SPEED_WINDOW] average, so it stays the hard ceiling nothing declared
         * or detected can raise. It moved by 2 m/s when the other ceilings went
         * up, rather than by the same amount: this is the number that has to keep
         * a car out, so it gets the least slack of any of them.
         */
        const val ABSOLUTE_MAX_SPEED_MPS = 22.0

        /** Play Services confidences are 0-100; 70 is a firm signal. */
        const val VEHICLE_CONFIDENCE = 70
        const val HUMAN_CONFIDENCE = 60

        /** Classifications older than this are ignored. */
        const val ACTIVITY_MAX_AGE_MS = 30_000L

        /**
         * How long blocked movement is tolerated before it costs a strike.
         *
         * Was 30 s, when a single expiry ended the walk outright. Ninety seconds
         * covers the things that were ending honest walks — a stop on a bus, a
         * lift across a junction, a stretch of fixes arriving in a burst — and
         * three of them still has to be survived before anything is lost.
         */
        const val GRACE_MS = 90_000L

        /**
         * Warnings a walk gets before it is thrown away.
         *
         * Three rather than one because the failure is asymmetric: a driver
         * caught on the third strike still claims nothing, while a walker caught
         * wrongly on the first loses hours they can't re-walk.
         */
        const val MAX_STRIKES = 3

        /** Speed samples averaged together (≈15 s at a 3 s fix interval). */
        const val SPEED_WINDOW = 5
    }
}
