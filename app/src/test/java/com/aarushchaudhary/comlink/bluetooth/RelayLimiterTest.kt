package com.aarushchaudhary.comlink.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLimiterTest {

    @Test
    fun floodFromOneNeighbourIsCapped() {
        var now = 0L
        val limiter = RelayLimiter(maxPerWindow = 20, windowMs = 10_000L, clock = { now })
        val allowed = (1..1000).count { limiter.allow("AA") }
        assertEquals(20, allowed)
    }

    @Test
    fun budgetComesBackInTheNextWindow() {
        var now = 0L
        val limiter = RelayLimiter(maxPerWindow = 3, windowMs = 10_000L, clock = { now })
        repeat(3) { assertTrue(limiter.allow("AA")) }
        assertFalse(limiter.allow("AA"))
        now = 10_000L
        assertTrue(limiter.allow("AA"))
    }

    @Test
    fun oneFloodingNeighbourDoesNotBlockOthers() {
        var now = 0L
        val limiter = RelayLimiter(maxPerWindow = 3, windowMs = 10_000L, clock = { now })
        repeat(10) { limiter.allow("ATTACKER") }
        assertTrue(limiter.allow("FRIEND"))
    }

    @Test
    fun cleanupKeepsEveryNewNeighbourAllowed() {
        // 10,000 different addresses force several clean-ups of old windows along the way
        var now = 0L
        val limiter = RelayLimiter(maxPerWindow = 1, windowMs = 1_000L, clock = { now })
        for (i in 0 until 10_000) {
            now += 10
            assertTrue(limiter.allow("addr-$i"))
        }
    }
}
