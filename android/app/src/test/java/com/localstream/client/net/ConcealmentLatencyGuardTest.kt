package com.localstream.client.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConcealmentLatencyGuardTest {
    private var now = 0L
    private val guard = ConcealmentLatencyGuard { now }

    private fun feed(latencyMs: Int, count: Int, stepMs: Long = 16L): Int {
        var triggers = 0
        repeat(count) {
            now += stepMs
            if (guard.onFrameDecoded(latencyMs)) triggers++
        }
        return triggers
    }

    @Test
    fun decoderThatStaysSlowAfterSkip_requestsExactlyOneIdr() {
        feed(15, 60)
        guard.onGapSkipped()
        // Field case: 12-19 ms before, ~50 ms after, for the rest of the session.
        assertEquals(1, feed(50, 200))
    }

    @Test
    fun decoderThatRecoversAfterSkip_neverRequestsIdr() {
        feed(15, 60)
        guard.onGapSkipped()
        feed(80, 20) // the concealment/refresh wave itself may be briefly slow
        assertEquals(0, feed(16, 200))
    }

    @Test
    fun smallJitterAboveBaseline_isNotASlowdown() {
        feed(15, 60)
        guard.onGapSkipped()
        assertEquals(0, feed(28, 200)) // +13 ms: below the +20 ms floor
    }

    @Test
    fun skipWithoutBaseline_isIgnored() {
        feed(15, 3)
        guard.onGapSkipped()
        assertEquals(0, feed(60, 200))
    }

    @Test
    fun cooldown_preventsIdrLoopOnAPermanentlySlowDecoder() {
        feed(15, 60)
        guard.onGapSkipped()
        assertEquals(1, feed(50, 100))
        feed(50, 60) // new (slow) baseline after the IDR
        guard.onGapSkipped()
        assertFalse(feed(200, 100) > 0) // within the 10 s cooldown
    }
}
