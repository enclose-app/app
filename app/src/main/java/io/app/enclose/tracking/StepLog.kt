package io.app.enclose.tracking

/** Where a count of steps came from. Stored by name, so a source added later still reads back. */
enum class StepSource {
    /** The phone's own hardware step counter, held by [LocationService]. */
    PHONE,

    /** The watch's passive step data, sent over the Data Layer. */
    WATCH,
}

/**
 * Steps one source counted between two wall-clock times, epoch ms.
 *
 * A source reports these back to back while it is counting, each starting where
 * the last one ended — including across stretches where it counted none. That
 * run of intervals is what lets a stretch with no steps in it read as *zero
 * steps* rather than as *nobody was counting*, and the difference matters: the
 * first is evidence against a walk, the second is no evidence at all.
 */
data class StepInterval(
    val source: StepSource,
    val fromEpochMs: Long,
    val toEpochMs: Long,
    val steps: Int,
)

/**
 * Answers "how many steps between these two times?" — or says nobody knows.
 *
 * **Missing evidence is never zero.** A stretch only counts as stepless when its
 * source was counting across all of it ([COVERAGE_SLACK_MS] aside). A process
 * restart, a denied permission or a phone with no step sensor leaves a hole in
 * the record, and a hole answers null, which [StretchJudge] treats as no evidence
 * either way. It is the rule the tracker already follows for location: silence
 * is not evidence of speed.
 *
 * An interval that straddles the edge of a query is shared out in proportion to
 * the time inside it, which is all a count with two timestamps can say.
 *
 * Pure, so it is tested on plain intervals.
 */
class StepLog(intervals: List<StepInterval>) {

    private val bySource: Map<StepSource, List<StepInterval>> = intervals
        .filter { it.toEpochMs > it.fromEpochMs && it.steps >= 0 }
        .groupBy { it.source }
        .mapValues { (_, list) -> list.sortedBy { it.fromEpochMs } }

    /** True when [source] has reported anything at all. */
    fun has(source: StepSource): Boolean = bySource[source].orEmpty().isNotEmpty()

    /**
     * Steps [source] counted between [fromEpochMs] and [toEpochMs], or null when
     * it wasn't counting across all of it.
     *
     * Up to [COVERAGE_SLACK_MS] of the span may go uncovered — the edges of a
     * stretch and the edges of a report rarely line up to the millisecond — and
     * the count is scaled up over what was missed, so a small hole at the edge
     * can't read as a shortfall in steps.
     */
    fun stepsBetween(source: StepSource, fromEpochMs: Long, toEpochMs: Long): Int? {
        val span = toEpochMs - fromEpochMs
        if (span <= 0L) return null
        val overlap = overlap(source, fromEpochMs, toEpochMs) ?: return null
        if (span - overlap.coveredMs > COVERAGE_SLACK_MS || overlap.coveredMs <= 0L) return null
        return Math.round(overlap.steps * span / overlap.coveredMs).toInt()
    }

    /**
     * Every step [source] counted between the two times, however patchily it was
     * counting — the figure to *show*, never one to judge by. Null when it
     * counted over none of the span.
     */
    fun counted(source: StepSource, fromEpochMs: Long, toEpochMs: Long): Int? {
        if (toEpochMs <= fromEpochMs) return null
        val overlap = overlap(source, fromEpochMs, toEpochMs) ?: return null
        if (overlap.coveredMs <= 0L) return null
        return Math.round(overlap.steps).toInt()
    }

    /** Every step [source] has counted, or null when it has counted nothing at all. */
    fun total(source: StepSource): Int? =
        bySource[source]?.takeIf { it.isNotEmpty() }?.sumOf { it.steps }

    private class Overlap(val coveredMs: Long, val steps: Double)

    private fun overlap(source: StepSource, fromEpochMs: Long, toEpochMs: Long): Overlap? {
        val list = bySource[source] ?: return null
        var steps = 0.0
        // Covered time is the union of the intervals inside the span, so two
        // reports of the same moment can't make a hole look covered twice over.
        var covered = 0L
        var coveredUntil = fromEpochMs
        for (iv in list) {
            if (iv.fromEpochMs >= toEpochMs) break
            val a = maxOf(iv.fromEpochMs, fromEpochMs)
            val b = minOf(iv.toEpochMs, toEpochMs)
            if (b <= a) continue
            steps += iv.steps.toDouble() * (b - a) / (iv.toEpochMs - iv.fromEpochMs)
            val start = maxOf(a, coveredUntil)
            if (b > start) {
                covered += b - start
                coveredUntil = b
            }
        }
        return Overlap(covered, steps)
    }

    companion object {
        /**
         * How much of a span may go unreported and still count as covered. The
         * phone reports every ten seconds and a stretch is thirty, so this absorbs
         * the edges without letting a real gap in the counting through.
         */
        const val COVERAGE_SLACK_MS = 5_000L
    }
}
