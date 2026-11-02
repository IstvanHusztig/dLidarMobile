package com.dlidar.mobile.debug.linktest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FloodMeterTest {

    @Test
    fun `counts lines, gaps and the rate`() {
        val meter = FloodMeter(expected = 4, startedAtMillis = 0)
        assertFalse(meter.onLine("echo hi", 10))
        meter.onLine("seq=00001 xxxx", 250)
        meter.onLine("seq=00002 xxxx", 500)
        meter.onLine("seq=00004 xxxx", 1000)

        val stats = meter.stats()
        assertEquals(3, stats.received)
        assertEquals(1, stats.missing)
        assertEquals(1, stats.outOfOrder)
        assertEquals(45L, stats.bytes)
        assertEquals(45L, stats.bytesPerSecond)
        assertEquals(listOf(15), stats.lineSizes)
    }
}
