package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.Mae
import kotlin.math.abs
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the bounded whole-frame white-balance estimation (issue #117).
 *
 * The whole-frame [FinishingPipeline] path no longer estimates gains at full resolution:
 * above [TiledFinishing.ANALYSIS_TARGET_PIXELS] it estimates ONCE on the same
 * block-averaged analysis frame the tiled path already uses, then applies the gains at
 * full resolution via [WhiteBalance.applyGains]; at or under the budget the analysis
 * frame IS the frame, so nothing changes. Three proofs, mirroring the
 * TiledFinishingStatsApproximationTest delta-gate pattern:
 *
 *  - the analysis-frame gains track the full-resolution gains within 0.5% on a cast
 *    scene (the same bar the tiled stats approximation is held to);
 *  - the end-to-end whole-frame finish stays metric-equivalent (MAE < 0.5/channel) to a
 *    reference finish that estimates at full resolution -- the previous call shape,
 *    replicated in-test;
 *  - a frame at or under the analysis budget finishes BIT-IDENTICALLY to that reference,
 *    under DEFAULT and RENDITION both, so sub-1 MP captures (every golden fixture
 *    included) are untouched by the bounded path.
 */
class FinishingPipelineBoundedWbTest {

    @Test
    fun analysisFrameGainsTrackFullResolutionGainsWithinHalfAPercent() {
        // Above the 1 MP analysis budget with a solid cast, so the estimate is well
        // outside the neutral deadband and the relative delta is meaningful.
        val scene = withCast(colorfulScene(1200, 1000, 0x9CL), rGain = 1.28, bGain = 0.78)
        assertTrue("scene must exceed the analysis budget", TiledFinishing.analysisDownsampleFactor(1200, 1000) > 1)

        val full = WhiteBalance.estimateGains(scene)
        val analysis = WhiteBalance.estimateGains(TiledFinishing.analysisFrame(scene))
        val rDelta = relDelta(full.rGain, analysis.rGain)
        val bDelta = relDelta(full.bGain, analysis.bGain)
        println(
            "whole-frame WB gains full=(r=%.4f b=%.4f) analysis=(r=%.4f b=%.4f) rDelta=%.4f%% bDelta=%.4f%%".format(
                full.rGain, full.bGain, analysis.rGain, analysis.bGain, rDelta * 100, bDelta * 100,
            ),
        )
        assertTrue("rGain delta ${rDelta * 100}% exceeds 0.5%", rDelta < 0.005)
        assertTrue("bGain delta ${bDelta * 100}% exceeds 0.5%", bDelta < 0.005)
    }

    @Test
    fun wholeFrameFinishIsMetricEquivalentToFullResolutionEstimation() {
        // Above the analysis budget (bounded estimation engages) but below the 9 MP tiled
        // threshold (the whole-frame path is the one under test).
        val scene = withCast(colorfulScene(1200, 1000, 0x44L), rGain = 1.22, bGain = 0.80)
        val bounded = FinishingPipeline.apply(scene, FinishingParams.DEFAULT)
        val reference = referenceFinish(scene, FinishingParams.DEFAULT)
        val mae = Mae.between(bounded, reference)
        println("bounded-WB whole-frame vs full-res-estimation MAE = %.5f".format(mae))
        assertTrue("bounded-WB MAE $mae exceeds the 0.5/channel equivalence bound", mae < 0.5)
    }

    @Test
    fun frameWithinAnalysisBudgetFinishesBitIdentically() {
        // 0.8 MP: at or under ANALYSIS_TARGET_PIXELS the analysis frame is the frame
        // itself, so the bounded path must reproduce the previous full-resolution
        // estimation byte for byte.
        val scene = withCast(colorfulScene(1000, 800, 0x21L), rGain = 1.30, bGain = 0.76)
        assertSame("analysis frame must be the frame itself", scene, TiledFinishing.analysisFrame(scene))

        for (params in listOf(FinishingParams.DEFAULT, FinishingParams.RENDITION)) {
            val bounded = FinishingPipeline.apply(scene, params)
            val reference = referenceFinish(scene, params)
            assertTrue(
                "sub-budget frame must finish bit-identically to full-res estimation",
                bounded.argb.contentEquals(reference.argb),
            )
        }
    }

    /**
     * The previous whole-frame call shape, replicated: backlit rescue first, then
     * [WhiteBalance.apply] estimating at FULL resolution, then the rest of the chain
     * unchanged (white balance and the rescue re-disabled so neither runs twice).
     */
    private fun referenceFinish(frame: Frame, params: FinishingParams): Frame {
        val rescued = FinishingPipeline.applyBacklitRescue(frame, params)
        val balanced = WhiteBalance.apply(rescued, params.whiteBalance)
        return FinishingPipeline.apply(balanced, params.copy(whiteBalance = 0.0, backlitRescue = 0.0))
    }

    private fun relDelta(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        return abs(a - b) / abs(a).coerceAtLeast(1e-9)
    }

    // --- deterministic content ----------------------------------------------------------

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun withCast(frame: Frame, rGain: Double, bGain: Double): Frame {
        val src = frame.argb
        val out = IntArray(src.size) { i ->
            val p = src[i]
            argb(
                (((p shr 16) and 0xFF) * rGain).toInt(),
                (p shr 8) and 0xFF,
                ((p and 0xFF) * bGain).toInt(),
            )
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    private fun colorfulScene(width: Int, height: Int, seed: Long): Frame {
        val r = lattice(width, height, seed xor 0x111111L, cell = 40)
        val g = lattice(width, height, seed xor 0x222222L, cell = 52)
        val b = lattice(width, height, seed xor 0x333333L, cell = 34)
        val out = IntArray(width * height) { i -> argb(r[i], g[i], b[i]) }
        return Frame(width, height, out, timestampMillis = 3L)
    }

    private fun lattice(width: Int, height: Int, seed: Long, cell: Int): IntArray {
        val lcg = SyntheticImages.Lcg(seed)
        val gridWidth = width / cell + 2
        val gridHeight = height / cell + 2
        val grid = IntArray(gridWidth * gridHeight) { lcg.nextByte() }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gridWidth + gx]
                val v10 = grid[gy * gridWidth + gx + 1]
                val v01 = grid[(gy + 1) * gridWidth + gx]
                val v11 = grid[(gy + 1) * gridWidth + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[y * width + x] = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
