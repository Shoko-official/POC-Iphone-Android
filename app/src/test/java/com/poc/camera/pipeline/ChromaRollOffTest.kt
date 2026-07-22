package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit proofs for the pure pieces of [ChromaRollOff]: the chroma-magnitude shoulder's
 * continuity at its knee, its monotonicity and asymptote, the strength blend endpoints
 * (0 = bit-exact passthrough, 1 = full compression) and its intermediate blend, and the
 * invariant that gray (zero-chroma) pixels are left untouched. All numbers are closed-form,
 * so the tests are deterministic and machine-independent.
 */
class ChromaRollOffTest {

    private val params = ChromaRollOffParams.DEFAULT

    // --- shoulder math -------------------------------------------------------------

    @Test
    fun shoulderIsIdentityBelowKneeAndContinuousAcrossIt() {
        assertEquals(10.0, ChromaRollOff.shoulder(10.0, params), 0.0)
        assertEquals(params.knee, ChromaRollOff.shoulder(params.knee, params), 0.0)
        // Continuity + unit slope at the knee: just above, the value barely departs from it.
        val justAbove = ChromaRollOff.shoulder(params.knee + 1e-6, params)
        assertEquals(params.knee, justAbove, 1e-5)
        assertTrue("shoulder must keep rising just past the knee", justAbove > params.knee)
    }

    @Test
    fun shoulderMatchesClosedFormAndStaysUnderAsymptote() {
        val asymptote = params.knee + params.soft
        for (mag in doubleArrayOf(35.0, 50.0, 85.0, 120.0, 400.0)) {
            val over = mag - params.knee
            val expected = params.knee + over / (1.0 + over / params.soft)
            assertEquals("shoulder($mag)", expected, ChromaRollOff.shoulder(mag, params), 1e-9)
            assertTrue("shoulder must stay under the knee+soft asymptote", ChromaRollOff.shoulder(mag, params) < asymptote)
        }
    }

    @Test
    fun shoulderIsMonotonicallyIncreasing() {
        var prev = -1.0
        var mag = 0.0
        while (mag <= 300.0) {
            val s = ChromaRollOff.shoulder(mag, params)
            assertTrue("shoulder must be non-decreasing in magnitude", s >= prev)
            prev = s
            mag += 2.5
        }
    }

    // --- strength blend endpoints --------------------------------------------------

    @Test
    fun strengthZeroIsBitExactPassthrough() {
        val frame = saturated(8, 8, 200, 50, 50)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 0.0))
        assertSame("strength 0 must return the input frame untouched", frame, out)
    }

    @Test
    fun strengthOneFullyCompressesAnExtremeChromaPixel() {
        val frame = saturated(16, 16, 200, 50, 50)
        val before = chromaMag(frame, 8, 8)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0))
        val after = chromaMag(out, 8, 8)
        // The uniform patch is far above the knee, so full strength compresses it toward the
        // knee+soft asymptote -- a large, measurable chroma reduction.
        assertTrue("extreme chroma must be compressed at full strength ($before -> $after)", after < before * 0.6)
    }

    @Test
    fun strengthBlendIsBetweenIdentityAndFull() {
        val frame = saturated(16, 16, 200, 50, 50)
        val orig = chromaMag(frame, 8, 8)
        val half = chromaMag(ChromaRollOff.apply(frame, params.copy(strength = 0.5)), 8, 8)
        val full = chromaMag(ChromaRollOff.apply(frame, params.copy(strength = 1.0)), 8, 8)
        // A partial strength sits strictly between the untouched original and full compression.
        assertTrue("half strength must compress less than full ($half vs $full)", half > full)
        assertTrue("half strength must still compress vs the original ($half vs $orig)", half < orig)
    }

    // --- gray pixels untouched -----------------------------------------------------

    @Test
    fun grayPixelsAreLeftUntouched() {
        // R == G == B -> zero chroma magnitude, below any knee -> exact identity, even at
        // full strength and across the whole 0..255 range.
        for (v in intArrayOf(0, 30, 128, 200, 255)) {
            val frame = saturated(8, 8, v, v, v)
            val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0))
            assertTrue("gray $v must be untouched", frame.argb.contentEquals(out.argb))
        }
    }

    @Test
    fun outputIsAlwaysOpaque() {
        val frame = Frame(4, 4, IntArray(16) { 0x00_C8_32_32.toInt() }, timestampMillis = 1L)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0))
        for (px in out.argb) {
            assertEquals("alpha must be forced opaque", 0xFF, (px ushr 24) and 0xFF)
        }
    }

    // --- param validation ----------------------------------------------------------

    @Test
    fun paramsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(knee = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(soft = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(strength = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(strength = -0.1) }
    }

    // --- helpers -------------------------------------------------------------------

    private fun saturated(w: Int, h: Int, r: Int, g: Int, b: Int): Frame {
        val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Frame(w, h, IntArray(w * h) { px }, timestampMillis = 1L)
    }

    private fun chromaMag(frame: Frame, x: Int, y: Int): Double {
        val px = frame.argb[y * frame.width + x]
        val r = ((px shr 16) and 0xFF).toDouble()
        val g = ((px shr 8) and 0xFF).toDouble()
        val b = (px and 0xFF).toDouble()
        val yl = 0.299 * r + 0.587 * g + 0.114 * b
        val cr = r - yl
        val cb = b - yl
        return sqrt(cr * cr + cb * cb)
    }
}
