package io.app.enclose.tracking

/**
 * Separates time spent moving from time spent standing at traffic lights.
 *
 * Pace over total elapsed time punishes people for the city they walk in: two
 * identical efforts read differently because one had more crossings. This keeps
 * a moving-time total so pace describes the walking rather than the waiting.
 *
 * Standing still is decided from two signals, either of which is enough:
 * the activity classifier reporting STILL, and speed under [STILL_SPEED_MPS].
 * The speed rule is what makes this work with the physical-activity permission
 * denied, which is the same reason [MotionGate] never relies on the classifier
 * alone.
 *
 * Time is only ever credited *between* two fixes, so a gap in recording — the
 * screen off, the process killed — can't be counted as either moving or paused.
 * That biases the total slightly low, which is the right direction: a pace that
 * flatters is worse than one that doesn't.
 *
 * Stateful per walk; call [reset] when one starts.
 */
class PauseTracker {

    /** Milliseconds credited as actually moving. */
    var movingMs: Long = 0L
        private set

    private var lastElapsedMs: Long? = null

    fun reset(movingMs: Long = 0L) {
        this.movingMs = movingMs
        lastElapsedMs = null
    }

    /**
     * Account for the interval ending at [nowElapsedMs] (monotonic). Returns the
     * running moving total.
     *
     * @param speedMps best available speed, or null when unknown.
     * @param motion latest classification, or null when unavailable.
     */
    fun update(nowElapsedMs: Long, speedMps: Double?, motion: MotionSample?): Long {
        val previous = lastElapsedMs
        lastElapsedMs = nowElapsedMs
        if (previous == null || nowElapsedMs <= previous) return movingMs

        val delta = nowElapsedMs - previous
        // A long gap means recording stopped, not that the walker stood still
        // for that long; crediting it either way would be an invention.
        if (delta > MAX_CREDITED_GAP_MS) return movingMs

        if (!isStopped(nowElapsedMs, speedMps, motion)) movingMs += delta
        return movingMs
    }

    private fun isStopped(
        nowElapsedMs: Long,
        speedMps: Double?,
        motion: MotionSample?,
    ): Boolean {
        val fresh = motion != null && (nowElapsedMs - motion.atElapsedMs) <= ACTIVITY_MAX_AGE_MS
        val classifiedStill = fresh &&
            motion!!.activity == MotionActivity.STILL &&
            motion.confidence >= STILL_CONFIDENCE
        val slow = speedMps != null && speedMps.isFinite() && speedMps < STILL_SPEED_MPS
        return classifiedStill || slow
    }

    companion object {
        /**
         * ~1.8 km/h. Below this nobody is making progress on foot, but it stays
         * clear of a genuinely slow uphill trudge, which should still count.
         */
        const val STILL_SPEED_MPS = 0.5

        /** Play Services confidences are 0-100; matches [MotionGate]'s bar. */
        const val STILL_CONFIDENCE = 60

        /** Classifications older than this say nothing about right now. */
        const val ACTIVITY_MAX_AGE_MS = 30_000L

        /**
         * Fixes arrive about every 3 s, so a gap this long means recording was
         * interrupted rather than that the interval is simply quiet.
         */
        const val MAX_CREDITED_GAP_MS = 60_000L
    }
}
