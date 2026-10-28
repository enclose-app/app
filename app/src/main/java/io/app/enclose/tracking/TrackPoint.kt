package io.app.enclose.tracking

/**
 * What is known about one recorded point besides where it is — the evidence a
 * stretch of the walk is judged on at Stop (see [StretchJudge]).
 *
 * Kept beside [TrackingManager.WalkState.path], index for index, rather than in
 * it: a position is all most of the app needs, and every reader of the path
 * would otherwise have to unwrap one.
 */
data class TrackPoint(
    /**
     * When the fix was taken, epoch ms. Null for a point with no clock behind it —
     * a tap in test mode, a point replayed from a GPX file, or one stored by a
     * build that didn't keep times — and a stretch with no clock is never judged.
     */
    val atEpochMs: Long?,
    /**
     * The straight line *to* this point from the one before crosses ground no
     * fix observed: the signal went silent, the position snapped after being
     * frozen, or the process died. Such a line is judged as one stretch on its
     * own, over the whole time it took.
     */
    val afterGap: Boolean = false,
    /** What the classifier said, confidently, when the fix was taken. */
    val motion: MotionEvidence? = null,
)
