package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.Mae
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Characterises the DOWNSAMPLED-statistics approximation the production auto route uses.
 *
 * The whole-frame [FinishingPipeline] derives its two global statistics (white-balance
 * gains and the [DetailEnhancer] coring knee) from the full frame. The auto tiled route
 * derives them from a block-averaged analysis frame instead, to keep the stats pass cheap.
 * This test measures how far the downsampled statistics drift from the full-frame ones and,
 * more importantly, proves the END-TO-END output stays metric-equivalent to the whole-frame
 * finish (MAE well under 0.5 per channel), which is the honest acceptance bar since the
 * downsampled-stats path is deliberately NOT bit-identical.
 */
class TiledFinishingStatsApproximationTest {

    @Test
    fun whiteBalanceGainsFromDownsampleTrackTheFullFrameWithinHalfAPercent() {
        // A colour-cast scene so the estimator returns a solid non-identity gain (well
        // outside the neutral deadband), making the relative delta meaningful.
        val cast = withCast(colorfulScene(600, 600, 0xA1L), rGain = 1.30, bGain = 0.75)
        val full = FinishingStats.compute(cast, FinishingParams.DEFAULT)

        for (factor in intArrayOf(2, 4)) {
            val down = FinishingStats.compute(TiledFinishing.downsample(cast, factor), FinishingParams.DEFAULT)
            val rDelta = relDelta(full.wbGains.rGain, down.wbGains.rGain)
            val bDelta = relDelta(full.wbGains.bGain, down.wbGains.bGain)
            println(
                "WB gains full=(r=%.4f b=%.4f) down%dx=(r=%.4f b=%.4f) rDelta=%.4f%% bDelta=%.4f%%".format(
                    full.wbGains.rGain, full.wbGains.bGain, factor,
                    down.wbGains.rGain, down.wbGains.bGain, rDelta * 100, bDelta * 100,
                ),
            )
            assertTrue("rGain ${factor}x delta ${rDelta * 100}% exceeds 0.5%", rDelta < 0.005)
            assertTrue("bGain ${factor}x delta ${bDelta * 100}% exceeds 0.5%", bDelta < 0.005)
        }
    }

    @Test
    fun detailKneeApproximationIsSeamFreeAndOutputSafeEvenWhenExaggerated() {
        // The coring knee is the one global statistic a downsample does NOT track tightly:
        // it is a scale-relative noise/detail-floor measure, and block-averaging changes the
        // frequency content the small-radius guided filter sees, so the downsampled knee can
        // differ several-fold from the full-frame one. That is measured and REPORTED here.
        //
        // Crucially this does not threaten correctness: the knee is applied as a single
        // GLOBAL constant to every tile, so any value -- accurate or not -- is seam-free by
        // construction; the only question is fidelity to the whole-frame finish, which the
        // end-to-end MAE answers. This asserts that even with detail-enhance EXAGGERATED far
        // above the DEFAULT ship strength (so the knee has maximum leverage on pixels), the
        // downsampled-stats tiled output stays metric-equivalent to the whole-frame finish.
        val scene = withCast(colorfulScene(1200, 1000, 0x7BL), rGain = 1.15, bGain = 0.88)
        val full = FinishingStats.compute(scene, FinishingParams.DEFAULT)
        for (factor in intArrayOf(2, 4)) {
            val down = FinishingStats.compute(TiledFinishing.downsample(scene, factor), FinishingParams.DEFAULT)
            println(
                "detail knee full=%.4f down%dx=%.4f delta=%.1f%% (global constant -> seam-free regardless)".format(
                    full.detailKnee, factor, down.detailKnee, relDelta(full.detailKnee, down.detailKnee) * 100,
                ),
            )
        }

        val exaggerated = FinishingParams.DEFAULT.copy(detailEnhance = 0.8)
        val whole = FinishingPipeline.apply(scene, exaggerated)
        val autoTiled = TiledFinishing.apply(scene, exaggerated, tileSize = 256)
        val mae = Mae.between(whole, autoTiled)
        println("exaggerated-detail auto-tiled vs whole-frame MAE = %.5f".format(mae))
        assertTrue("exaggerated-detail MAE $mae exceeds the 0.5/channel equivalence bound", mae < 0.5)
    }

    @Test
    fun autoTiledOutputIsMetricEquivalentToWholeFrame() {
        // Frame above the 1 MP analysis-downsample budget (so the auto route actually
        // downsamples for stats) but below the 9 MP tiled-routing threshold (so
        // FinishingPipeline.apply is the untouched whole-frame reference).
        val scene = withCast(colorfulScene(1200, 1000, 0x33L), rGain = 1.20, bGain = 0.82)
        assertTrue("analysis frame should be downsampled", TiledFinishing.analysisDownsampleFactor(1200, 1000) > 1)

        val whole = FinishingPipeline.apply(scene, FinishingParams.DEFAULT)
        val autoTiled = TiledFinishing.apply(scene, FinishingParams.DEFAULT, tileSize = 256)

        val mae = Mae.between(whole, autoTiled)
        println("auto-tiled (downsampled stats) vs whole-frame MAE = %.5f".format(mae))
        assertTrue("auto-tiled MAE $mae exceeds the 0.5/channel equivalence bound", mae < 0.5)
    }

    private fun relDelta(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val denom = abs(a).coerceAtLeast(1e-9)
        return abs(a - b) / denom
    }

    // --- deterministic content -----------------------------------------------------

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r.coerceIn(0, 255)) shl 16) or ((g.coerceIn(0, 255)) shl 8) or b.coerceIn(0, 255)

    /**
     * A smooth colourful scene: three independent coarse LCG lattices (one per channel)
     * bilinearly interpolated up, giving flat-ish patches, gradients and edges -- the kind
     * of content a merged burst carries, with enough near-neutral area for the white-balance
     * estimator and enough texture for the detail knee.
     */
    private fun colorfulScene(width: Int, height: Int, seed: Long): Frame {
        val r = lattice(width, height, seed xor 0x111111L, cell = 40)
        val g = lattice(width, height, seed xor 0x222222L, cell = 52)
        val b = lattice(width, height, seed xor 0x333333L, cell = 34)
        val out = IntArray(width * height) { i -> argb(r[i], g[i], b[i]) }
        return Frame(width, height, out, timestampMillis = 3L)
    }

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
