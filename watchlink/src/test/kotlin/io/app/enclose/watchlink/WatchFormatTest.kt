package io.app.enclose.watchlink

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFormatTest {

    @Test
    fun `distance switches to km at a kilometre`() {
        assertEquals("840 m", WatchFormat.distance(840))
        assertEquals("1.00 km", WatchFormat.distance(1000))
        assertEquals("12.35 km", WatchFormat.distance(12_350))
    }

    @Test
    fun `area switches to km2 at a square kilometre`() {
        assertEquals("51235 m²", WatchFormat.area(51_235))
        assertEquals("1.25 km²", WatchFormat.area(1_250_000))
    }

    @Test
    fun `elapsed gains hours past an hour`() {
        assertEquals("0:05", WatchFormat.elapsed(5_400))
        assertEquals("12:03", WatchFormat.elapsed(723_000))
        assertEquals("1:02:03", WatchFormat.elapsed(3_723_000))
    }

    @Test
    fun `a start in the future reads as zero`() {
        assertEquals("0:00", WatchFormat.elapsed(-30_000))
    }
}
