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
 * (0 = bit-exact passthrough, 1 = full compression) and its intermediate blend, the
 * spatial isolation gate (issue #107: a uniformly saturated frame passes through
 * bit-exactly, an isolated spot compresses) and the invariant that gray (zero-chroma)
 * pixels are left untouched. All numbers are closed-form, so the tests are deterministic
 * and machine-independent.
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

    @Test
    fun shoulderAtAnExplicitKneeMatchesClosedForm() {
        // The gated apply loop calls the shoulder with the per-pixel EFFECTIVE knee, so the
        // explicit-knee overload must match the closed form at any knee.
        for (knee in doubleArrayOf(30.0, 45.0, 62.5)) {
            assertEquals(knee - 1.0, ChromaRollOff.shoulder(knee - 1.0, knee, params.soft), 0.0)
            val over = 80.0 - knee
            val expected = knee + over / (1.0 + over / params.soft)
            assertEquals("shoulder(80, knee=$knee)", expected, ChromaRollOff.shoulder(80.0, knee, params.soft), 1e-9)
        }
    }

    // --- strength blend endpoints --------------------------------------------------

    @Test
    fun strengthZeroIsBitExactPassthrough() {
        val frame = isolatedSpot()
        val out = ChromaRollOff.apply(frame, params.copy(strength = 0.0))
        assertSame("strength 0 must return the input frame untouched", frame, out)
    }

    @Test
    fun strengthOneFullyCompressesAnIsolatedExtremeChromaSpot() {
        val frame = isolatedSpot()
        val before = chromaMag(frame, SPOT_CENTER, SPOT_CENTER)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0))
        val after = chromaMag(out, SPOT_CENTER, SPOT_CENTER)
        // The spot's low-chroma surround keeps the effective knee at the global knee, so
        // full strength compresses it toward the knee+soft asymptote -- a large, measurable
        // chroma reduction.
        assertTrue("isolated extreme chroma must be compressed at full strength ($before -> $after)", after < before * 0.6)
    }

    @Test
    fun strengthBlendIsBetweenIdentityAndFull() {
        val frame = isolatedSpot()
        val orig = chromaMag(frame, SPOT_CENTER, SPOT_CENTER)
        val half = chromaMag(ChromaRollOff.apply(frame, params.copy(strength = 0.5)), SPOT_CENTER, SPOT_CENTER)
        val full = chromaMag(ChromaRollOff.apply(frame, params.copy(strength = 1.0)), SPOT_CENTER, SPOT_CENTER)
        // A partial strength sits strictly between the untouched original and full compression.
        assertTrue("half strength must compress less than full ($half vs $full)", half > full)
        assertTrue("half strength must still compress vs the original ($half vs $orig)", half < orig)
    }

    // --- the spatial isolation gate ------------------------------------------------

    @Test
    fun uniformlySaturatedFrameIsBitExactlyUntouched() {
        // A frame filled edge-to-edge with one extreme-chroma colour (magnitude ~85, far
        // above the global knee): the local mean equals the pixel magnitude everywhere, so
        // the effective knee (isolationFactor * localMean) sits well above the magnitude and
        // NOTHING compresses -- the issue #107 fix's core guarantee, at full strength.
        val frame = filled(64, 64, 190, 75, 82)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0))
        assertTrue("uniform saturation must pass through bit-exactly", frame.argb.contentEquals(out.argb))
    }

    @Test
    fun isolationFactorOneStillPassesUniformSaturationThrough() {
        // The gate's lower bound: even at isolationFactor 1.0 the effective knee equals the
        // (uniform) magnitude itself, and compression requires mag STRICTLY above the knee.
        val frame = filled(64, 64, 190, 75, 82)
        val out = ChromaRollOff.apply(frame, params.copy(strength = 1.0, isolationFactor = 1.0))
        assertTrue("uniform saturation must pass through at isolationFactor 1", frame.argb.contentEquals(out.argb))
    }

    // --- gray pixels untouched -----------------------------------------------------

    @Test
    fun grayPixelsAreLeftUntouched() {
        // R == G == B -> zero chroma magnitude, below any knee -> exact identity, even at
        // full strength and across the whole 0..255 range.
        for (v in intArrayOf(0, 30, 128, 200, 255)) {
            val frame = filled(8, 8, v, v, v)
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
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(isolationFactor = 0.9) }
        assertThrows(IllegalArgumentException::class.java) { ChromaRollOffParams(neighborhoodRadius = 0) }
    }

    // --- helpers -------------------------------------------------------------------

    private fun filled(w: Int, h: Int, r: Int, g: Int, b: Int): Frame {
        val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Frame(w, h, IntArray(w * h) { px }, timestampMillis = 1L)
    }

    /**
     * A 4x4 extreme-chroma spot (magnitude ~85) centred at ([SPOT_CENTER], [SPOT_CENTER])
     * on a near-gray surround (magnitude ~3): the surround keeps the spot's local mean far
     * below `knee / isolationFactor`, so its effective knee is the global knee and the spot
     * compresses along the ungated shoulder.
     */
    private fun isolatedSpot(): Frame {
        val size = 64
        val surround = (0xFF shl 24) or (120 shl 16) or (118 shl 8) or 116
        val spot = (0xFF shl 24) or (190 shl 16) or (75 shl 8) or 82
        val px = IntArray(size * size) { surround }
        for (y in SPOT_CENTER - 2 until SPOT_CENTER + 2) {
            for (x in SPOT_CENTER - 2 until SPOT_CENTER + 2) {
                px[y * size + x] = spot
            }
        }
        return Frame(size, size, px, timestampMillis = 1L)
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

    private companion object {
        const val SPOT_CENTER = 32
    }
}
