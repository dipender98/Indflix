package com.multimovies

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceSpeedTrackerTest {

    @AfterEach
    fun tearDown() {
        SourceSpeedTracker.reset()
    }

    @Test
    fun `averageLatency returns null for never measured source`() {
        assertNull(SourceSpeedTracker.averageLatency("never-seen"))
    }

    @Test
    fun `single success records exact average`() {
        SourceSpeedTracker.record("cineverse", 1500, true)
        assertEquals(1500.0, SourceSpeedTracker.averageLatency("cineverse"))
    }

    @Test
    fun `multiple successes average across calls`() {
        SourceSpeedTracker.record("cineverse", 1000, true)
        SourceSpeedTracker.record("cineverse", 3000, true)
        assertEquals(2000.0, SourceSpeedTracker.averageLatency("cineverse"))
    }

    @Test
    fun `failures add penalty demoting measured source`() {
        SourceSpeedTracker.record("cineverse", 1000, true)
        SourceSpeedTracker.record("cineverse", 0, false)
        // (1000 + 30000) / 1 = 31000
        assertEquals(31000.0, SourceSpeedTracker.averageLatency("cineverse")!!, 0.001)
    }

    @Test
    fun `only failures returns MAX_VALUE`() {
        SourceSpeedTracker.record("dead", 5000, false)
        SourceSpeedTracker.record("dead", 0, false)
        assertEquals(Double.MAX_VALUE, SourceSpeedTracker.averageLatency("dead"))
    }

    @Test
    fun `fast source ranks before slow`() {
        SourceSpeedTracker.record("fast", 500, true)
        SourceSpeedTracker.record("slow", 5000, true)
        assertTrue(SourceSpeedTracker.averageLatency("fast")!! < SourceSpeedTracker.averageLatency("slow")!!)
    }

    @Test
    fun `measured source sorts before unmeasured`() {
        SourceSpeedTracker.record("measured", 1000, true)
        assertTrue(
            (SourceSpeedTracker.averageLatency("measured") ?: Double.MAX_VALUE) < Double.MAX_VALUE
        )
    }

    @Test
    fun `failed source ranks after successful`() {
        SourceSpeedTracker.record("good", 2000, true)
        SourceSpeedTracker.record("bad", 8000, false)
        assertTrue(SourceSpeedTracker.averageLatency("good")!! < SourceSpeedTracker.averageLatency("bad")!!)
    }
}
