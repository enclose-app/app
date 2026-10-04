package io.app.enclose.tracking

import io.app.enclose.geo.Geo
import io.app.enclose.geo.LatLng

/**
 * Whether a fix could follow the last trusted one at all, given the time between
 * them and how sure the provider was of each.
 *
 * This is what keeps a single wild fix off the path. Losing GPS hands positioning
 * to Wi-Fi and cell towers, and those fixes routinely claim ±25 m while landing
 * hundreds of metres out — they pass [TrackingManager.MAX_ACCURACY_METERS] because
 * the accuracy they report is the thing that is wrong. One of them on the path is
 * a spike: out to nowhere and straight back.
 *
 * A jump is not *rejected* on this answer — a frozen fix snapping to the true
 * position is a jump too, and that one is real. [TrackingManager] holds a jump
 * back until the next fix says which it was. This only answers the question; it
 * never sees the motion gate, which still judges every fix as before.
 */
object FixPlausibility {

    /**
     * The speed no fix may imply. [MotionGate.ABSOLUTE_MAX_SPEED_MPS]: past it the
     * movement is not human-powered, so a fix needing it to be reached is either
     * a fault or a vehicle — and a vehicle is the gate's call, not this.
     */
    const val MAX_SPEED_MPS = MotionGate.ABSOLUTE_MAX_SPEED_MPS

    /**
     * Never call anything shorter than this a jump, whatever the timing. A burst of
     * fixes milliseconds apart would otherwise make ordinary GPS wander look
     * impossible.
     */
    const val MIN_JUMP_METERS = 30.0

    /**
     * True when [to] is further from [from] than anyone could have got in the time
     * between them, after allowing for both fixes' stated accuracy. A missing
     * accuracy counts as zero: it adds no slack it can't vouch for.
     */
    fun isJump(
        from: LatLng,
        fromAtElapsedMs: Long,
        fromAccuracyMeters: Float?,
        to: LatLng,
        toAtElapsedMs: Long,
        toAccuracyMeters: Float?,
    ): Boolean {
        val seconds = (toAtElapsedMs - fromAtElapsedMs).coerceAtLeast(0L) / 1000.0
        val slack = (fromAccuracyMeters ?: 0f).toDouble() + (toAccuracyMeters ?: 0f).toDouble()
        val reachable = maxOf(MIN_JUMP_METERS, MAX_SPEED_MPS * seconds + slack)
        return Geo.distanceMeters(from, to) > reachable
    }
}
