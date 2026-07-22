package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proofs for the pure pieces of [FoliageMask]: the green-sector chroma membership
 * (rejecting cyan, yellow and blue that border green), the mid-luma band, the olive/
 * yellow-green boundary softness, and the grayscale all-zero invariant. Closed-form where
 * possible, so deterministic.
 */
class FoliageMaskTest {

    // --- chroma sector membership --------------------------------------------------

    @Test
    fun chromaFiresOnGreenAndRejectsBorderingHues() {
        assertTrue("foliage green must score high", FoliageMask.chromaLikelihood(60.0, 110.0, 45.0) > 0.7)
        // Cyan carries a positive Cg but also a positive Cb -> rejected by the notBlue gate.
        assertTrue("cyan must be rejected (positive Cb)", FoliageMask.chromaLikelihood(120.0, 210.0, 215.0) < 0.05)
        // Blue has a negative-to-tiny Cg -> below the green knee.
        assertTrue("blue must be rejected", FoliageMask.chromaLikelihood(110.0, 155.0, 210.0) < 0.05)
        // Red is far from green.
        assertTrue("red must be rejected", FoliageMask.chromaLikelihood(200.0, 30.0, 40.0) < 0.05)
        assertEquals("neutral must be exactly 0", 0.0, FoliageMask.chromaLikelihood(128.0, 128.0, 128.0), 0.0)
    }

    @Test
    fun oliveYellowGreenBoundaryIsSoftNotHard() {
        // Deep yellow is (mostly) rejected; an olive that is greener than yellow gets a PARTIAL,
        // non-zero membership -- the documented soft boundary rather than a hard cutoff.
        val yellow = FoliageMask.chromaLikelihood(225.0, 215.0, 90.0)
        val olive = FoliageMask.chromaLikelihood(120.0, 135.0, 70.0)
        assertTrue("deep yellow must be largely rejected (was $yellow)", yellow < 0.15)
        assertTrue("olive must get a partial, non-zero membership (was $olive)", olive in 0.05..0.95)
    }

    // --- luma band -----------------------------------------------------------------

    @Test
    fun lumaBandPassesMidtonesAndExcludesCrushedAndBlown() {
        assertEquals("mid luma passes fully", 1.0, FoliageMask.lumaBand(58.0, 108.0, 52.0), 1e-9)
        assertEquals("crushed shadow is excluded", 0.0, FoliageMask.lumaBand(6.0, 10.0, 6.0), 1e-9)
        assertEquals("blown highlight is excluded", 0.0, FoliageMask.lumaBand(250.0, 250.0, 250.0), 1e-9)
    }

    // --- grayscale invariant + determinism -----------------------------------------

    @Test
    fun grayscaleFrameYieldsAllZeroMask() {
        val v = 120
        val px = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        val mask = FoliageMask.compute(Frame(32, 32, IntArray(32 * 32) { px }, 0L))
        assertTrue("a grayscale frame must produce an all-zero foliage mask", mask.all { it == 0.0 })
    }

    @Test
    fun computeFiresOnAGreenFieldAndIsDeterministic() {
        val w = 24
        val h = 24
        val green = (0xFF shl 24) or (60 shl 16) or (110 shl 8) or 45
        val frame = Frame(w, h, IntArray(w * h) { green }, 0L)
        val a = FoliageMask.compute(frame)
        val b = FoliageMask.compute(frame)
        for (i in a.indices) assertEquals(a[i], b[i], 0.0)
        // Interior (clear of the box-blur edge falloff) fires strongly.
        var sum = 0.0
        var n = 0
        for (y in 6 until h - 6) for (x in 6 until w - 6) {
            sum += a[y * w + x]; n++
        }
        assertTrue("a green field interior must fire (was ${sum / n})", sum / n > 0.7)
    }
}
