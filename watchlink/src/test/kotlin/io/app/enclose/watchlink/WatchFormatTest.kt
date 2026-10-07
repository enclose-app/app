package io.app.enclose.watchlink

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFormatTest {

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
