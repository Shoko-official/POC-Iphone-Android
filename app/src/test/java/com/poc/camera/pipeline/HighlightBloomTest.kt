package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HighlightBloomTest {

    private fun run(
        r: Float,
        g: Float,
        b: Float,
        background: Float,
        threshold: Double,
        boost: Double,
    ): FloatArray {
        val src = floatArrayOf(r)
        val srcG = floatArrayOf(g)
        val srcB = floatArrayOf(b)
        val bg = floatArrayOf(background)
        val outR = FloatArray(1)
        val outG = FloatArray(1)
        val outB = FloatArray(1)
        HighlightBloom.apply(
            srcR = src, srcG = srcG, srcB = srcB,
            backgroundness = bg, size = 1,
            threshold = threshold, boost = boost,
            outR = outR, outG = outG, outB = outB,
            chunkCount = 1,
        )
        return floatArrayOf(outR[0], outG[0], outB[0])
    }

    /**
     * Regression guard for the smoothstep ramp on the shipping path: a background pixel whose
     * luma sits exactly halfway in the [threshold, 255] band gets the smoothstep-weighted boost,
     * unchanged by the degenerate-span guard added for issue #157.
     */
    @Test
    fun midBandPixelGetsSmoothstepWeightedBoost() {
        // luma of (245,245,245) = 245 (Rec.601 weights sum to 1). t = (245-235)/20 = 0.5.
        // smoothstep(0.5) = 0.5 -> ramp = 0.5 -> gain = 1 + (3-1)*0.5 = 2.0 -> out = 490.
        val out = run(245f, 245f, 245f, background = 1f, threshold = 235.0, boost = 3.0)
        assertEquals(490f, out[0], 1e-3f)
        assertEquals(490f, out[1], 1e-3f)
        assertEquals(490f, out[2], 1e-3f)
    }

    /** Pixel below the threshold is untouched (gain 1). */
    @Test
    fun subThresholdPixelIsPassthrough() {
        val out = run(200f, 200f, 200f, background = 1f, threshold = 235.0, boost = 3.0)
        assertEquals(200f, out[0], 1e-3f)
        assertEquals(200f, out[1], 1e-3f)
        assertEquals(200f, out[2], 1e-3f)
    }

    /** boost <= 1 is an exact passthrough regardless of luma. */
    @Test
    fun boostOneIsPassthrough() {
        val out = run(255f, 255f, 255f, background = 1f, threshold = 235.0, boost = 1.0)
        assertEquals(255f, out[0], 0f)
        assertEquals(255f, out[1], 0f)
        assertEquals(255f, out[2], 0f)
    }

    /**
     * Issue #157: highlightThreshold == 255.0 is a value BokehParams permits. A pure-white
     * background pixel then hits smoothstep(255, 255, 255) — a 0/0 span that previously produced
     * NaN and crashed the render. The guard must instead give a clean hard step (full boost on
     * the clipped pixel) and never emit NaN.
     */
    @Test
    fun threshold255WhitePixelIsFiniteAndFullyBoosted() {
        val out = run(255f, 255f, 255f, background = 1f, threshold = 255.0, boost = 3.0)
        assertFalse("output R must not be NaN", out[0].isNaN())
        assertFalse("output G must not be NaN", out[1].isNaN())
        assertFalse("output B must not be NaN", out[2].isNaN())
        // Hard step at 255: a fully-clipped white pixel gets the full boost (gain 3 -> 765).
        assertEquals(765f, out[0], 1e-3f)
        assertEquals(765f, out[1], 1e-3f)
        assertEquals(765f, out[2], 1e-3f)
    }

    /**
     * With threshold == 255, a pixel just below pure white is below the hard step, so it is left
     * untouched — and, critically, still finite (no NaN leaking from the degenerate span).
     */
    @Test
    fun threshold255SubWhitePixelIsFinitePassthrough() {
        val out = run(254f, 254f, 254f, background = 1f, threshold = 255.0, boost = 3.0)
        assertFalse(out[0].isNaN())
        assertEquals(254f, out[0], 1e-3f)
        assertEquals(254f, out[1], 1e-3f)
        assertEquals(254f, out[2], 1e-3f)
    }
}
