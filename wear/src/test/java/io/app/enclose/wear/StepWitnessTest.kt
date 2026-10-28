package io.app.enclose.wear

import io.app.enclose.watchlink.WatchStatus
import io.app.enclose.watchlink.WatchStatus.Phase
import io.app.enclose.watchlink.WatchSteps
import io.app.enclose.watchlink.WatchSteps.Interval
import io.app.enclose.wear.StepWitness.Action
import io.app.enclose.wear.StepWitness.Kept
import io.app.enclose.wear.StepWitness.Registration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepWitnessTest {

    private val walk = 1_700_000_000_000L
    private val other = walk + 3_600_000L
    private val idle = WatchStatus()
    private val closed = WatchStatus(phase = Phase.CLOSED, startedAtEpochMs = walk)
    private val beforeFirstFix = WatchStatus(phase = Phase.WALKING)
    private val walking = WatchStatus(phase = Phase.WALKING, startedAtEpochMs = walk)

    private fun minute(n: Int, steps: Int = 100) =
        Interval(walk + n * 60_000L, walk + (n + 1) * 60_000L, steps)

    // --- when to count ---

    @Test
    fun `counting starts when a walk does, before its first fix`() {
        assertEquals(Action.REGISTER, StepWitness.actionFor(Registration.NONE, beforeFirstFix))
    }

    @Test
    fun `a walk already counted for is left alone`() {
        assertEquals(Action.KEEP, StepWitness.actionFor(Registration.CURRENT, walking))
    }

    @Test
    fun `a registration from before a reboot or an update is made again`() {
        assertEquals(Action.REGISTER, StepWitness.actionFor(Registration.STALE, walking))
    }

    @Test
    fun `counting stops when the walk ends, however it ends`() {
        assertEquals(Action.CLEAR, StepWitness.actionFor(Registration.CURRENT, idle))
        assertEquals(Action.CLEAR, StepWitness.actionFor(Registration.CURRENT, closed))
        assertEquals(Action.CLEAR, StepWitness.actionFor(Registration.STALE, idle))
    }

    @Test
    fun `nothing to do between walks`() {
        assertEquals(Action.KEEP, StepWitness.actionFor(Registration.NONE, idle))
    }

    // --- which walk the steps are for ---

    @Test
    fun `steps counted before the first fix belong to the walk`() {
        assertEquals(Kept(walk, listOf(minute(0))), StepWitness.follow(Kept(null, listOf(minute(0))), walking))
    }

    @Test
    fun `the same walk keeps its steps`() {
        val kept = Kept(walk, listOf(minute(0), minute(1)))
        assertEquals(kept, StepWitness.follow(kept, walking))
    }

    @Test
    fun `another walk starts from nothing`() {
        val next = WatchStatus(phase = Phase.WALKING, startedAtEpochMs = other)
        assertEquals(Kept(other), StepWitness.follow(Kept(walk, listOf(minute(0))), next))
    }

    @Test
    fun `a new walk before its first fix starts from nothing`() {
        // The last walk's end was never heard; a walk with no start yet can't be it.
        assertEquals(Kept(), StepWitness.follow(Kept(walk, listOf(minute(0))), beforeFirstFix))
    }

    @Test
    fun `the end of a walk drops its steps`() {
        assertEquals(Kept(), StepWitness.follow(Kept(walk, listOf(minute(0))), closed))
        assertEquals(Kept(), StepWitness.follow(Kept(walk, listOf(minute(0))), idle))
    }

    // --- joining a batch ---

    @Test
    fun `a batch joins in time order, without what overlaps`() {
        val overlapping = Interval(walk + 30_000L, walk + 90_000L, 120)
        assertEquals(
            listOf(minute(0), minute(1), minute(2)),
            StepWitness.merge(listOf(minute(0)), listOf(minute(2), overlapping, minute(1))),
        )
    }

    @Test
    fun `the same batch delivered twice counts once`() {
        val once = StepWitness.merge(emptyList(), listOf(minute(0), minute(1)))
        assertEquals(once, StepWitness.merge(once, listOf(minute(0), minute(1))))
    }

    @Test
    fun `an interval that makes no sense is left out`() {
        val backwards = Interval(walk + 60_000L, walk, 50)
        val negative = Interval(walk + 120_000L, walk + 180_000L, -1)
        assertEquals(listOf(minute(0)), StepWitness.merge(emptyList(), listOf(minute(0), backwards, negative)))
    }

    @Test
    fun `zero steps are kept, since the phone can only gain from them`() {
        assertEquals(listOf(minute(0, steps = 0)), StepWitness.merge(emptyList(), listOf(minute(0, steps = 0))))
    }

    @Test
    fun `a full ledger keeps the newest`() {
        val full = List(WatchSteps.MAX_INTERVALS) { minute(it) }
        val merged = StepWitness.merge(full, listOf(minute(WatchSteps.MAX_INTERVALS)))
        assertEquals(WatchSteps.MAX_INTERVALS, merged.size)
        assertEquals(minute(1), merged.first())
        assertEquals(minute(WatchSteps.MAX_INTERVALS), merged.last())
    }

    // --- kept between deliveries ---

    @Test
    fun `steps survive being stored`() {
        val intervals = listOf(minute(0), minute(1, steps = 0), minute(2, steps = 87))
        assertEquals(intervals, StepWitness.decode(StepWitness.encode(intervals)))
    }

    @Test
    fun `nothing stored is nothing held`() {
        assertTrue(StepWitness.decode(null).isEmpty())
        assertTrue(StepWitness.decode("").isEmpty())
    }

    @Test
    fun `an entry that can't be read costs only itself`() {
        val stored = StepWitness.encode(listOf(minute(0))) + ";garbage;1,2" + ";" + StepWitness.encode(listOf(minute(1)))
        assertEquals(listOf(minute(0), minute(1)), StepWitness.decode(stored))
    }
}
