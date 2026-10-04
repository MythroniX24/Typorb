package com.typorb.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tuning rules encode what a budget phone can actually sustain (Redmi 8A: four Cortex-A53 at
 * ~1.8 GHz, 3 GB RAM), so they are pinned down as plain functions.
 */
class SessionTuningTest {

    @Test
    fun `never uses every core, leaving headroom for the keyboard and UI`() {
        assertEquals(1, SessionTuning.intraOpThreads(1))
        assertEquals(1, SessionTuning.intraOpThreads(2))
        assertEquals(3, SessionTuning.intraOpThreads(4)) // Redmi 8A
        assertEquals(4, SessionTuning.intraOpThreads(8))
        assertEquals(4, SessionTuning.intraOpThreads(16)) // small matmuls stop scaling
    }

    @Test
    fun `inter-op stays sequential`() {
        assertEquals(1, SessionTuning.INTER_OP_THREADS)
    }

    @Test
    fun `decoder budget grows with the take length`() {
        val short = SessionTuning.maxDecoderSteps(2_000)
        val medium = SessionTuning.maxDecoderSteps(8_000)
        val long = SessionTuning.maxDecoderSteps(25_000)
        assertTrue(short < medium)
        assertTrue(medium < long)
        assertEquals(448, SessionTuning.maxDecoderSteps(Long.MAX_VALUE / 2))
    }

    @Test
    fun `decoder budget is always positive even for a zero-length buffer`() {
        assertTrue(SessionTuning.maxDecoderSteps(0) > 0)
        assertTrue(SessionTuning.maxDecoderSteps(-1) > 0)
    }

    @Test
    fun `gpu backend is only offered on modern platforms`() {
        assertFalse(SessionTuning.isNnapiEligible(26)) // Android 8
        assertFalse(SessionTuning.isNnapiEligible(28)) // Android 9 — Redmi 8A ships here
        assertTrue(SessionTuning.isNnapiEligible(29)) // Android 10
        assertTrue(SessionTuning.isNnapiEligible(34))
    }
}