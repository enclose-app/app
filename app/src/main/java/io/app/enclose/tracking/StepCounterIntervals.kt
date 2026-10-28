package io.app.enclose.tracking

/**
 * Turns a running step total into back-to-back [StepInterval]s.
 *
 * A hardware step counter reports one number, the steps taken since boot, and
 * reports it only when it changes. That is not yet evidence about any stretch of
 * a walk: a car journey produces no readings at all, which is indistinguishable
 * from a counter that stopped. So the counter is read on a heartbeat instead —
 * each [report] closes an interval at the moment everything counted so far is
 * known to have arrived, *including when nothing was counted*. The unbroken run
 * of intervals that results is what lets [StepLog] tell zero steps from nobody
 * counting.
 *
 * Intervals are bounded by report times rather than by the readings' own
 * timestamps. A reading's timestamp is when its last step was taken, which for
 * the first reading after registering can be hours old, and the counter confirms
 * steps a few seconds late; bounding by report time can only shift steps a few
 * seconds into the next interval, and never loses one.
 *
 * Pure, so it is tested on plain numbers; [PhoneStepCounter] feeds it.
 */
class StepCounterIntervals(private val source: StepSource = StepSource.PHONE) {

    /** The total at the last report, or at the first reading. */
    private var reportedTotal: Long? = null
    private var latestTotal: Long? = null
    private var reportedAtEpochMs: Long? = null

    /** Added to every raw reading after the counter reset, so totals keep climbing. */
    private var offset = 0L
    private var latestRaw: Long? = null

    /**
     * A reading of the counter: [rawTotal] steps since it started counting, as
     * received at [atEpochMs]. The first reading is the baseline, and counting
     * starts from the moment it arrived.
     */
    fun onReading(rawTotal: Long, atEpochMs: Long) {
        val previousRaw = latestRaw
        // A total that goes backwards means the counter started again from zero —
        // a sensor stack restart can do it. The steps before and after both count.
        if (previousRaw != null && rawTotal < previousRaw) offset += previousRaw
        latestRaw = rawTotal
        val total = rawTotal + offset
        if (latestTotal == null) {
            reportedTotal = total
            reportedAtEpochMs = atEpochMs
        }
        latestTotal = total
    }

    /**
     * Close the interval at [atEpochMs] with the steps counted since the last
     * report, or null when there is nothing to report yet: no reading has
     * arrived, or no time has passed.
     */
    fun report(atEpochMs: Long): StepInterval? {
        val from = reportedAtEpochMs ?: return null
        val base = reportedTotal ?: return null
        val total = latestTotal ?: return null
        if (atEpochMs <= from) return null
        reportedTotal = total
        reportedAtEpochMs = atEpochMs
        return StepInterval(
            source = source,
            fromEpochMs = from,
            toEpochMs = atEpochMs,
            steps = (total - base).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
