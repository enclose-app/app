package io.app.enclose.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {

    private val now = 1_790_000_000_000L
    private val set = DemoData.build(now)

    @Test
    fun `the same set every time, so a screenshot can be retaken`() {
        assertEquals(set, DemoData.build(now))
    }

    @Test
    fun `every claim has its walk, under the same id`() {
        val claimed = set.walks.filter { it.claimed }.map { it.id }.toSet()
        assertEquals(set.territories.map { it.id }.toSet(), claimed)
        assertEquals(set.territories.size, set.territories.map { it.id }.distinct().size)
    }

    @Test
    fun `claims are real shapes, placed and in the past`() {
        set.territories.forEach {
            assertTrue(it.name, it.areaSqMeters > 50_000)
            assertTrue(it.name, it.city.isNotBlank() && it.country.isNotBlank())
            assertTrue(it.name, it.claimedAtEpochMs < now)
        }
        set.walks.forEach { assertTrue(it.id, (it.durationMs ?: 0) > 0) }
    }

    @Test
    fun `ids can't collide with a real claim's`() {
        (set.territories.map { it.id } + set.walks.map { it.id } + set.voided.map { it.id })
            .forEach { assertTrue(it, it.startsWith("demo-")) }
    }
}
