package io.app.enclose.watchlink

import java.nio.ByteBuffer

/**
 * Steps the watch counted during the phone's walk: the walk's second witness.
 *
 * The phone judges each stretch of a walk on the steps taken in it, and the
 * watch's can only help — they can make a stretch count as walked, never the
 * reverse — so a watch that is flat, at home or out of range costs a walk
 * nothing. That is also why a lost message is cheap, and why every message
 * carries all of the walk's intervals the watch still has rather than only the
 * newest: the next one repairs whatever the last one lost. The phone drops what
 * it already has.
 *
 * Sent as a message ([WatchLink.STEPS_PATH]) rather than a data item: it is
 * evidence for one walk, wanted while that walk is running and by nothing
 * afterwards.
 */
data class WatchSteps(
    /** The walk they belong to: its [WatchStatus.startedAtEpochMs]. */
    val startedAtEpochMs: Long,
    /** Oldest first, each as the watch counted it. */
    val intervals: List<Interval> = emptyList(),
    /** Sent in answer to [WatchLink.STEPS_FLUSH_PATH]: nothing more is coming. */
    val flushed: Boolean = false,
) {
    /** Steps counted between two wall-clock times, epoch ms. */
    data class Interval(val fromEpochMs: Long, val toEpochMs: Long, val steps: Int)

    /**
     * The intervals travel packed into one byte array, [INTERVAL_BYTES] each,
     * rather than as arrays of longs: how the Data Layer encodes an array is its
     * own business, and a message over its limit is not sent at all, so the
     * size has to be known rather than estimated.
     */
    fun toFields(): Map<String, Any> = mapOf(
        K_VERSION to WatchStatus.VERSION,
        K_STARTED_AT to startedAtEpochMs,
        K_INTERVALS to ByteBuffer.allocate(intervals.size * INTERVAL_BYTES).apply {
            intervals.forEach {
                putLong(it.fromEpochMs)
                putLong(it.toEpochMs)
                putLong(it.steps.toLong())
            }
        }.array(),
        K_FLUSHED to flushed,
    )

    companion object {
        /**
         * Intervals per message, newest kept: 48 KB, under half of the 100 KB a
         * message carries. At one interval a minute it is more than a day of
         * walking. Whatever falls off the front went in an earlier message.
         */
        const val MAX_INTERVALS = 2_000

        /** From, to and steps, a long each. */
        const val INTERVAL_BYTES = 3 * Long.SIZE_BYTES

        /**
         * Reads [toFields] back, or null for a message this build can't read. An
         * interval that makes no sense — ending before it began, a negative
         * count — is left out rather than costing the rest.
         */
        fun fromFields(get: (String) -> Any?): WatchSteps? {
            if (get(K_VERSION) != WatchStatus.VERSION) return null
            val startedAt = get(K_STARTED_AT) as? Long ?: return null
            val packed = get(K_INTERVALS) as? ByteArray ?: return null
            if (packed.size % INTERVAL_BYTES != 0) return null
            val buffer = ByteBuffer.wrap(packed)
            val intervals = List(packed.size / INTERVAL_BYTES) { Triple(buffer.long, buffer.long, buffer.long) }
                .filter { (from, to, steps) -> to > from && steps in 0L..Int.MAX_VALUE.toLong() }
                .map { (from, to, steps) -> Interval(from, to, steps.toInt()) }
            return WatchSteps(startedAt, intervals, flushed = get(K_FLUSHED) as? Boolean ?: false)
        }

        /**
         * The payload of [WatchLink.STEPS_FLUSH_PATH]: the start of the walk the
         * phone is asking about, so the watch can answer for that walk even when
         * the status naming it hasn't reached the watch yet.
         */
        fun flushRequest(startedAtEpochMs: Long): ByteArray =
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(startedAtEpochMs).array()

        /** The walk a [flushRequest] asks about, or null for a payload this build can't read. */
        fun flushRequestOf(payload: ByteArray): Long? =
            payload.takeIf { it.size == Long.SIZE_BYTES }?.let { ByteBuffer.wrap(it).long }

        private const val K_VERSION = "v"
        private const val K_STARTED_AT = "startedAt"
        private const val K_INTERVALS = "intervals"
        private const val K_FLUSHED = "flushed"
    }
}
