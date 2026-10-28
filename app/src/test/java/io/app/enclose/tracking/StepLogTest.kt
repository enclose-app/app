package io.app.enclose.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps between two times — or nobody knows. The difference between *zero steps*
 * and *no count* is the whole of what this pins: the first is evidence against a
 * walk, the second is no evidence at all, and mistaking one for the other either
 * waves a drive through or bridges a walk.
 */
class StepLogTest {

    @Test
    fun `a span the counter covered reads its steps`() {
        val log = StepLog(listOf(phone(0, 10, 14), phone(10, 20, 15), phone(20, 30, 16)))

        assertEquals(45, log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `a report across the edge of a span is shared out by time`() {
        val log = StepLog(listOf(phone(0, 10, 20), phone(10, 20, 10)))

        assertEquals(15, log.stepsBetween(StepSource.PHONE, 5_000L, 15_000L))
    }

    @Test
    fun `zero steps counted is evidence, not a hole`() {
        val log = StepLog(listOf(phone(0, 10, 0), phone(10, 20, 0), phone(20, 30, 0)))

        assertEquals(0, log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `a hole in the counting is no evidence at all`() {
        // The process died for ten seconds, and nothing was counting.
        val log = StepLog(listOf(phone(0, 10, 14), phone(20, 30, 14)))

        assertNull(log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `a few seconds missed at the edge are scaled over, not read as a shortfall`() {
        val log = StepLog(listOf(phone(3, 30, 27)))

        assertEquals(30, log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `a source that never reported knows nothing`() {
        val log = StepLog(listOf(phone(0, 30, 40)))

        assertNull(log.stepsBetween(StepSource.WATCH, 0L, 30_000L))
        assertFalse(log.has(StepSource.WATCH))
        assertNull(log.total(StepSource.WATCH))
        assertTrue(log.has(StepSource.PHONE))
    }

    @Test
    fun `the same moment reported twice doesn't cover a hole`() {
        val log = StepLog(listOf(phone(0, 10, 14), phone(0, 10, 14)))

        assertNull(log.stepsBetween(StepSource.PHONE, 0L, 20_000L))
    }

    @Test
    fun `what was counted is shown even where it can't be judged`() {
        val log = StepLog(listOf(phone(0, 10, 14), phone(20, 30, 16)))

        assertEquals(30, log.counted(StepSource.PHONE, 0L, 30_000L))
        assertNull(log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `reports out of order are read in order`() {
        val log = StepLog(listOf(phone(20, 30, 16), phone(0, 10, 14), phone(10, 20, 15)))

        assertEquals(45, log.stepsBetween(StepSource.PHONE, 0L, 30_000L))
    }

    @Test
    fun `malformed reports are ignored`() {
        val log = StepLog(listOf(phone(10, 10, 5), phone(10, 5, 5), phone(0, 10, -1)))

        assertFalse(log.has(StepSource.PHONE))
    }

    @Test
    fun `a total adds up everything one source counted`() {
        val log = StepLog(listOf(phone(0, 10, 14), watch(0, 10, 12), phone(10, 20, 15)))

        assertEquals(29, log.total(StepSource.PHONE))
        assertEquals(12, log.total(StepSource.WATCH))
    }

    @Test
    fun `an empty span has no answer`() {
        val log = StepLog(listOf(phone(0, 30, 40)))

        assertNull(log.stepsBetween(StepSource.PHONE, 10_000L, 10_000L))
        assertNull(log.counted(StepSource.PHONE, 10_000L, 5_000L))
    }

    // --- helpers -------------------------------------------------------------

    private fun phone(fromS: Int, toS: Int, steps: Int) =
        StepInterval(StepSource.PHONE, fromS * 1_000L, toS * 1_000L, steps)

    private fun watch(fromS: Int, toS: Int, steps: Int) =
        StepInterval(StepSource.WATCH, fromS * 1_000L, toS * 1_000L, steps)
}
