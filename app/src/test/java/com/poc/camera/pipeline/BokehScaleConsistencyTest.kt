package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.PipelineBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scale-consistency proof for [BokehParams.forImageWidth] (issue #128), following the same
 * pattern [ChromaRollOffScalingTest] established for the chroma gate (issue #114): the same
 * scene rendered at width W with the W-scaled params and at 2W (nearest-neighbor upscale of
 * BOTH the frame and the mask) with the 2W-scaled params should produce the SAME LOOK -- the
 * 2W output, downsampled back to W, matches the base-W output within a small tolerance. The
 * #114-style degradation guard is reproduced too: the SAME 2x scene rendered with the
 * UNSCALED (base-W) params must diverge by clearly more, proving the width scaling in
 * [BokehParams.forImageWidth] is what preserves the look, not the blur/gather math alone.
 *
 * The base width [BASE_W] is chosen so its 2x lands EXACTLY on [BokehParams.REFERENCE_WIDTH]
 * (1000 -> 2000): the base params are a clean half-scale (radius 6) and the "2x" params are
 * the shipped [BokehParams.DEFAULT] itself (radius 12), not an arbitrary pair of test widths.
 *
 * Unlike the chroma gate, the disc gather is not exact: [DiscBlur] samples a FIXED tapCount
 * (24, unscaled by [BokehParams.forImageWidth] -- see its KDoc) over a disc whose AREA grows
 * with the square of the radius, so doubling the radius quarters the sample density per unit
 * area. That sparser sampling at the larger scale is a known, honest approximation error the
 * tolerance below absorbs; it is bounded (documented at the assertion) and stays small next
 * to the unscaled-params divergence the degradation guard proves against.
 */
class BokehScaleConsistencyTest {

    @Test
    fun rendererIsScaleConsistentWhenParamsScaleWithWidth() {
        val baseParams = BokehParams.forImageWidth(BASE_W)
        val scaledParams = BokehParams.forImageWidth(2 * BASE_W)
        // Confirm the widths land where the KDoc says: a clean half-scale at the base, and
        // exactly the shipped DEFAULT (not merely "some radius") at 2x -- see the class KDoc.
        assertEquals(6, baseParams.maxBlurRadius)
        assertEquals(BokehParams.DEFAULT, scaledParams)

        val base = PipelineBenchmark.bokehScene(BASE_W, BASE_H, SEED)
        val baseOut = BokehRenderer.render(base.frame, base.mask, baseParams)

        val upFrame = upscale2xNearest(base.frame)
        val upMask = upscale2xNearestMask(base.mask, BASE_W, BASE_H)

        val scaledDown = downsample2xMean(BokehRenderer.render(upFrame, upMask, scaledParams))
        // The #114 escape, reproduced: the SAME 2x scene rendered with the base-W (unscaled)
        // params under-blurs relative to the physically-doubled content -- half the intended
        // radius relative to the enlarged scene.
        val unscaledDown = downsample2xMean(BokehRenderer.render(upFrame, upMask, baseParams))

        val scaledDelta = delta(baseOut, scaledDown)
        val unscaledDelta = delta(baseOut, unscaledDown)
        println(
            "[bokeh-scaling] scale-consistency delta: scaled params mean=%.4f max=%d, unscaled params mean=%.4f max=%d"
                .format(scaledDelta.mean, scaledDelta.max, unscaledDelta.mean, unscaledDelta.max),
        )
        assertTrue(
            "scaled-params mean delta ${scaledDelta.mean} must stay under $MAX_SCALED_MEAN_DELTA",
            scaledDelta.mean < MAX_SCALED_MEAN_DELTA,
        )
        assertTrue(
            "scaled-params max channel delta ${scaledDelta.max} must stay under $MAX_SCALED_MAX_DELTA",
            scaledDelta.max <= MAX_SCALED_MAX_DELTA,
        )
        // The unscaled params must diverge by clearly more than the scaled ones -- the
        // measured proof that width-scaling the params is what preserves the look.
        assertTrue(
            "unscaled params must diverge more (unscaled max ${unscaledDelta.max} vs scaled max ${scaledDelta.max})",
            unscaledDelta.max >= MIN_UNSCALED_MAX_DELTA && unscaledDelta.max > 2 * scaledDelta.max,
        )
    }

    // --- fixtures / helpers (mirror ChromaRollOffScalingTest's) ---------------------

    /** Nearest-neighbour 2x upscale: each source pixel becomes a 2x2 block. */
    private fun upscale2xNearest(frame: Frame): Frame {
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * 2 * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = frame.argb[y * w + x]
                val row0 = (2 * y) * (2 * w) + 2 * x
                val row1 = (2 * y + 1) * (2 * w) + 2 * x
                out[row0] = px
                out[row0 + 1] = px
                out[row1] = px
                out[row1 + 1] = px
            }
        }
        return Frame(2 * w, 2 * h, out, frame.timestampMillis)
    }

    /** Nearest-neighbour 2x upscale of a mask plane -- the same block-doubling as [upscale2xNearest]. */
    private fun upscale2xNearestMask(mask: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * 2 * h * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = mask[y * w + x]
                val row0 = (2 * y) * (2 * w) + 2 * x
                val row1 = (2 * y + 1) * (2 * w) + 2 * x
                out[row0] = v
                out[row0 + 1] = v
                out[row1] = v
                out[row1 + 1] = v
            }
        }
        return out
    }

    /** 2x2 box-mean downsample (rounded per channel), the inverse of [upscale2xNearest]. */
    private fun downsample2xMean(frame: Frame): Frame {
        val w = frame.width / 2
        val h = frame.height / 2
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = frame.argb[(2 * y + dy) * frame.width + (2 * x + dx)]
                        r += (px shr 16) and 0xFF
                        g += (px shr 8) and 0xFF
                        b += px and 0xFF
                    }
                }
                out[y * w + x] = argb((r / 4.0).roundToInt(), (g / 4.0).roundToInt(), (b / 4.0).roundToInt())
            }
        }
        return Frame(w, h, out, frame.timestampMillis)
    }

    private class Delta(val mean: Double, val max: Int)

    /** Mean and max per-channel absolute difference over two same-size frames. */
    private fun delta(a: Frame, b: Frame): Delta {
        var sum = 0L
        var maxDiff = 0
        for (i in a.argb.indices) {
            val pa = a.argb[i]
            val pb = b.argb[i]
            val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            val db = abs((pa and 0xFF) - (pb and 0xFF))
            sum += (dr + dg + db).toLong()
            maxDiff = max(maxDiff, max(dr, max(dg, db)))
        }
        return Delta(sum.toDouble() / (a.argb.size * 3L), maxDiff)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private companion object {
        // Half REFERENCE_WIDTH (2000), so 2x lands exactly on it -- see the class KDoc.
        const val BASE_W = 1000
        const val BASE_H = 750
        const val SEED = 0xB0BEA7L

        // MEASURED 2026-07-23 on the deterministic fixture. Actuals -> baked bound (with
        // margin), documented at the assertion sites:
        //   scale-consistency, scaled params   : mean 0.0519 / max 16 -> 0.08 / 24 (headroom
        //     for the fixed-24-tap sparser-sampling approximation the class KDoc documents --
        //     the residual here is real, not test noise: the 2x radius disc covers 4x the
        //     area at the same tap count, so its gather is a coarser statistical sample of
        //     the same underlying texture/highlight content)
        //   scale-consistency, unscaled params : mean 0.4171 / max 154 -> floor 100, and > 2x
        //     the scaled max (the #114-style escape made measurable: the unscaled radius is
        //     HALF what the doubled scene needs, so its disc gather is far too tight relative
        //     to the physically-enlarged subject and background texture)
        const val MAX_SCALED_MEAN_DELTA = 0.08
        const val MAX_SCALED_MAX_DELTA = 24
        const val MIN_UNSCALED_MAX_DELTA = 100
    }
}
