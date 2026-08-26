package com.ottmirror

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostThrottlerTest {

    @Test
    fun nextInterval_doublesAndCaps() {
        assertEquals(2000L, HostThrottler.nextInterval(0L))
        assertEquals(4000L, HostThrottler.nextInterval(2000L))
        assertEquals(8000L, HostThrottler.nextInterval(4000L))
        assertEquals(60_000L, HostThrottler.nextInterval(60_000L))
        assertEquals(60_000L, HostThrottler.nextInterval(60_000_000L))
    }

    @Test
    fun backoffAndSuccess_roundTrip() {
        HostThrottler.reset()
        val host = "net52.cc"
        assertEquals(1000L, HostThrottler.currentInterval(host))
        HostThrottler.recordBackoff(host)
        assertEquals(2000L, HostThrottler.currentInterval(host))
        HostThrottler.recordBackoff(host)
        assertEquals(4000L, HostThrottler.currentInterval(host))
        HostThrottler.recordSuccess(host)
        assertEquals(1000L, HostThrottler.currentInterval(host))
    }

    @Test
    fun reset_clearsState() {
        HostThrottler.recordBackoff("a")
        HostThrottler.recordBackoff("a")
        assertTrue(HostThrottler.currentInterval("a") > 1000L)
        HostThrottler.reset()
        assertEquals(1000L, HostThrottler.currentInterval("a"))
    }
}