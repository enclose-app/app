package io.app.enclose.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class WatchStepsTest {

    private val walk = 1_700_000_000_000L
    private val first = WatchSteps.Interval(walk, walk + 60_000L, 104)
    private val second = WatchSteps.Interval(walk + 60_000L, walk + 120_000L, 98)

    private fun packed(vararg intervals: Triple<Long, Long, Long>): ByteArray =
        ByteBuffer.allocate(intervals.size * WatchSteps.INTERVAL_BYTES).apply {
            intervals.forEach { (from, to, steps) -> putLong(from).putLong(to).putLong(steps) }
        }.array()

    @Test
    fun `steps survive the round trip`() {
        val steps = WatchSteps(walk, listOf(first, second), flushed = true)
        val fields = steps.toFields()
        assertEquals(steps, WatchSteps.fromFields { fields[it] })
    }

    @Test
    fun `no steps yet survives the round trip`() {
        val fields = WatchSteps(walk).toFields()
        assertEquals(WatchSteps(walk), WatchSteps.fromFields { fields[it] })
    }

    @Test
    fun `a message without the flushed flag is an ordinary batch`() {
        val fields = WatchSteps(walk, listOf(first)).toFields() - "flushed"
        assertFalse(WatchSteps.fromFields { fields[it] }!!.flushed)
    }

    @Test
    fun `intervals cut short are unreadable`() {
        val whole = WatchSteps(walk, listOf(first, second)).toFields()["intervals"] as ByteArray
        val fields = WatchSteps(walk).toFields() + ("intervals" to whole.copyOf(whole.size - 1))
        assertNull(WatchSteps.fromFields { fields[it] })
    }

    @Test
    fun `an interval that makes no sense is left out, and the rest kept`() {
        val fields = WatchSteps(walk).toFields() + (
            "intervals" to packed(
                Triple(walk, walk + 60_000L, 104L),
                Triple(walk + 60_000L, walk + 60_000L, 5L),
                Triple(walk + 120_000L, walk + 180_000L, -3L),
            )
        )
        assertEquals(listOf(first), WatchSteps.fromFields { fields[it] }!!.intervals)
    }

    @Test
    fun `another version is unreadable, not misread`() {
        val fields = WatchSteps(walk, listOf(first)).toFields() + ("v" to WatchStatus.VERSION - 1)
        assertNull(WatchSteps.fromFields { fields[it] })
    }

    @Test
    fun `a message with no walk is unreadable`() {
        val fields = WatchSteps(walk, listOf(first)).toFields() - "startedAt"
        assertNull(WatchSteps.fromFields { fields[it] })
    }

    @Test
    fun `a full message fits the Data Layer with room to spare`() {
        val full = WatchSteps(
            walk,
            List(WatchSteps.MAX_INTERVALS) { WatchSteps.Interval(walk + it * 60_000L, walk + (it + 1) * 60_000L, 100) },
        )
        val packed = full.toFields()["intervals"] as ByteArray
        // A message holds 100 KB; the rest of the fields are a few dozen bytes.
        assertTrue(packed.size < 100 * 1024 / 2)
    }

    @Test
    fun `a flush request names the walk it asks about`() {
        assertEquals(walk, WatchSteps.flushRequestOf(WatchSteps.flushRequest(walk)))
    }

    @Test
    fun `a flush request this build can't read names no walk`() {
        assertNull(WatchSteps.flushRequestOf(ByteArray(0)))
        assertNull(WatchSteps.flushRequestOf(ByteArray(12)))
    }
}
