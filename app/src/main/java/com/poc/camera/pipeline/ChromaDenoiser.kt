package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Luma-guided chroma noise reduction. After a burst merge, residual sensor noise
 * that survives spatial/temporal averaging shows up mostly as chroma speckle: the
 * per-channel read/shot noise decorrelates the R, G and B values so a pixel drifts in
 * hue even where luma is clean. This pass smooths that speckle while leaving luma and
 * genuine colour edges intact.
 *
 * It works in a simple Rec. 601 full-range opponent space (documented axes, not a
 * calibrated YCbCr transform):
 *
 *   Y  = 0.299R + 0.587G + 0.114B      // luma, left UNTOUCHED
 *   Cb = B - Y                         // blue-difference chroma axis
 *   Cr = R - Y                         // red-difference chroma axis
 *
 * Each chroma plane is filtered with the cross-guided [GuidedFilter] using LUMA as the
 * guide. Because the guided output is a locally linear function of luma, the chroma
 * planes flatten wherever luma is flat (the interior of a uniform patch, exactly where
 * chroma speckle lives) yet keep their transition wherever luma has an edge -- and most
 * real chroma edges co-occur with a luma edge, so colour boundaries survive. A
 * [strength] in [0, 1] blends the filtered chroma back over the original (0 = off,
 * 1 = fully filtered), so the effect is tunable without a hard on/off.
 *
 * Reconstruction inverts the opponent transform exactly (luma preserved):
 *
 *   R = Y + Cr
 *   B = Y + Cb
 *   G = Y - (0.299*Cr + 0.114*Cb) / 0.587
 *
 * RGB is clamped to [0, 255] and alpha forced opaque. Pure Kotlin, deterministic, no
 * Android dependencies.
 *
 * Limitation: an equiluminant chroma edge (a hue change with no luma step) is not seen
 * by the luma guide and will be softened like speckle. Such edges are rare in real
 * captures; the synthetic colour edges in the quality harness all carry a luma step.
 */
object ChromaDenoiser {

    /**
     * Guided-filter window half-width. Chroma noise is high-frequency, so a small
     * window suffices to flatten it; 4 is tuned for the [SyntheticScenes]-class 128px
     * fixtures (a larger window smears colour across the closely-spaced patches of the
     * colorchart golden). A full-resolution capture warrants scaling this up.
     */
    const val DEFAULT_RADIUS = 4

    /**
     * Guided-filter edge regulariser in squared 8-bit LUMA units. The default treats
     * luma swings under ~3% of full scale as flat (chroma smoothed through them) and
     * preserves anything stronger as an edge, so saturated patch interiors flatten
     * while the colour transitions at luma edges stay sharp.
     */
    const val DEFAULT_EPS = (0.03 * 255.0) * (0.03 * 255.0)

    // Rec. 601 luma weights, matching [Luminance] / [LocalToneMapper].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Denoises the chroma of [frame], preserving its dimensions, timestamp and luma.
     *
     * @param strength blend of filtered vs. original chroma in [0, 1] (0 = off).
     * @param radius guided-filter window half-width on the chroma planes.
     * @param eps guided-filter luma-edge regulariser in squared intensity units.
     */
    fun apply(
        frame: Frame,
        strength: Double,
        radius: Int = DEFAULT_RADIUS,
        eps: Double = DEFAULT_EPS,
    ): Frame {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        val src = frame.argb
        if (strength == 0.0) {
            // No-op fast path, but still normalise alpha to opaque like the full path.
            return Frame(frame.width, frame.height, IntArray(src.size) { (0xFF shl 24) or (src[it] and 0x00FFFFFF) }, frame.timestampMillis)
        }

        // Opponent-space split and reconstruction are both element-wise (row-parallel);
        // the guided filtering between them is parallel internally.
        val n = src.size
        val luma = DoubleArray(n)
        val cb = DoubleArray(n)
        val cr = DoubleArray(n)
        PipelineParallel.parallelRows(n) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val r = ((pixel shr 16) and 0xFF).toDouble()
                val g = ((pixel shr 8) and 0xFF).toDouble()
                val b = (pixel and 0xFF).toDouble()
                val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                luma[i] = y
                cb[i] = b - y
                cr[i] = r - y
            }
        }

        val cbFiltered = GuidedFilter.guided(cb, luma, frame.width, frame.height, radius, eps)
        val crFiltered = GuidedFilter.guided(cr, luma, frame.width, frame.height, radius, eps)

        val out = IntArray(n)
        PipelineParallel.parallelRows(n) { start, end ->
            for (i in start until end) {
                val y = luma[i]
                val outCb = cb[i] + strength * (cbFiltered[i] - cb[i])
                val outCr = cr[i] + strength * (crFiltered[i] - cr[i])
                val r = (y + outCr).roundToInt().coerceIn(0, 255)
                val b = (y + outCb).roundToInt().coerceIn(0, 255)
                val g = (y - (R_WEIGHT * outCr + B_WEIGHT * outCb) / G_WEIGHT).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
