package io.app.enclose.wear

import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchSteps

/**
 * The decisions behind the watch's step witness, apart from Health Services and
 * the Data Layer so they can be tested: when to be counting, which walk the
 * counted steps belong to, and how a new batch joins what is held.
 * [StepRecorder] is the Android half.
 */
object StepWitness {

    enum class Action { REGISTER, CLEAR, KEEP }

    enum class Registration {
        /** Nothing registered. */
        NONE,

        /** Registered during this boot by this build: Health Services still has it. */
        CURRENT,

        /** Registered before a reboot or an update, either of which may have dropped it. */
        STALE,
    }

    /**
     * Counting is wanted only while the phone records a walk. The platform
     * batches it, so it costs next to nothing, but next to nothing for the hours
     * between walks is still waste. A stale registration is made again for a
     * walk — registering only replaces the old one — and cleared otherwise, or
     * Health Services would go on waking the app with steps nobody wants.
     */
    fun actionFor(registration: Registration, status: WatchStatus): Action = when {
        status.phase == WatchStatus.Phase.WALKING ->
            if (registration == Registration.CURRENT) Action.KEEP else Action.REGISTER
        registration != Registration.NONE -> Action.CLEAR
        else -> Action.KEEP
    }

    /**
     * The steps held for one walk. [walk] is its start, null until the phone has
     * sent one: the first fix can come well after the first step.
     */
    data class Kept(val walk: Long? = null, val intervals: List<WatchSteps.Interval> = emptyList())

    /**
     * What is held once [status] arrives. Kept while it describes the same walk,
     * and dropped once that walk is over or another has begun: steps from the
     * last walk would only vouch for ground they weren't taken on.
     */
    fun follow(kept: Kept, status: WatchStatus): Kept {
        if (status.phase != WatchStatus.Phase.WALKING) return Kept()
        val walk = status.startedAtEpochMs
        return when {
            kept.walk == walk -> kept
            // The walk's first fix: what was counted before it is this walk's.
            kept.walk == null -> kept.copy(walk = walk)
            else -> Kept(walk)
        }
    }

    /**
     * [batch] joined to [held], oldest first. Anything overlapping what is held
     * already is left out — the same minute counted twice would vouch for twice
     * the ground — and only the newest [WatchSteps.MAX_INTERVALS] are kept,
     * since that is all one message carries.
     */
    fun merge(held: List<WatchSteps.Interval>, batch: List<WatchSteps.Interval>): List<WatchSteps.Interval> {
        val merged = held.toMutableList()
        for (interval in batch.sortedBy { it.fromEpochMs }) {
            if (interval.toEpochMs <= interval.fromEpochMs || interval.steps < 0) continue
            if (merged.any { it.fromEpochMs < interval.toEpochMs && interval.fromEpochMs < it.toEpochMs }) continue
            merged += interval
        }
        return merged.sortedBy { it.fromEpochMs }.takeLast(WatchSteps.MAX_INTERVALS)
    }

    /** [Kept.intervals] as stored between deliveries: `from,to,steps`, joined by `;`. */
    fun encode(intervals: List<WatchSteps.Interval>): String =
        intervals.joinToString(";") { "${it.fromEpochMs},${it.toEpochMs},${it.steps}" }

    /** [encode] read back. An entry that can't be read is skipped rather than costing the rest. */
    fun decode(stored: String?): List<WatchSteps.Interval> =
        stored.orEmpty().split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@mapNotNull null
            val from = parts[0].toLongOrNull() ?: return@mapNotNull null
            val to = parts[1].toLongOrNull() ?: return@mapNotNull null
            val steps = parts[2].toIntOrNull() ?: return@mapNotNull null
            WatchSteps.Interval(from, to, steps)
        }
}
