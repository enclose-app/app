package io.app.enclose.tracking

/**
 * Running total of how much the walk has climbed.
 *
 * GPS altitude is far noisier than GPS position — readings wander by several
 * metres while standing still — so summing every upward difference would credit
 * a flat lap around a park with hundreds of metres of climb. A rise only counts
 * once it clears [MIN_GAIN_METERS] above the last confirmed level, and a
 * descent has to clear the same bar before it moves the reference down. That
 * hysteresis is what makes the number mean something on the flat, at the cost of
 * missing genuine bumps smaller than the noise floor — which is the right trade,
 * since a stat that inflates is worse than one that rounds down.
 *
 * Stateful and per-walk: call [reset] when one starts. Pure Kotlin so the
 * thresholds can be tested without a device.
 */
class ElevationAccumulator {

    /** Total confirmed climb in metres. Descent is not subtracted. */
    var gainMeters: Double = 0.0
        private set

    /** The last altitude accepted as real, rather than noise around it. */
    private var reference: Double? = null

    /** Forget the previous walk, optionally resuming from a stored total. */
    fun reset(gainMeters: Double = 0.0) {
        this.gainMeters = gainMeters
        reference = null
    }

    /**
     * Feed an altitude reading. Returns the running total, unchanged when the
     * reading is missing, unusable, or still inside the noise band.
     */
    fun add(altitudeMeters: Double?): Double {
        val altitude = altitudeMeters?.takeIf { it.isFinite() } ?: return gainMeters

        val previous = reference
        if (previous == null) {
            reference = altitude
            return gainMeters
        }

        val delta = altitude - previous
        when {
            delta >= MIN_GAIN_METERS -> {
                gainMeters += delta
                reference = altitude
            }
            // Coming back down: move the reference so the next climb is measured
            // from here, but never credit the descent as gain.
            delta <= -MIN_GAIN_METERS -> reference = altitude
        }
        return gainMeters
    }

    private companion object {
        /**
         * Below this, a change is indistinguishable from GPS altitude drift.
         * Barometer-free Android fixes are commonly ±5 m, so 3 m is already
         * permissive; raising it further would start missing real hills.
         */
        const val MIN_GAIN_METERS = 3.0
    }
}
