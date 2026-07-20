package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Mertens-style exposure fusion of a set of ALIGNED frames tagged with relative EV.
 *
 * SUPERSEDED ON THE HDR PATH: [HdrMergePipeline] now fuses with
 * [LaplacianExposureFusion], which does true multi-scale Laplacian-pyramid blending
 * and no longer leaves the residual low-frequency halos the box-blur ramp below can
 * produce around high-contrast exposure boundaries. This class is retained as the
 * documented single-scale baseline and as the comparison reference the halo test
 * measures the pyramid fusion against; see [LaplacianExposureFusion].
 *
 * Each frame contributes a per-pixel weight from [ExposureFusionWeights]; the fused
 * output is, per pixel and per channel, the weight-normalised sum of the inputs:
 *
 *   out(p, c) = ( sum_i wb_i(p) * frame_i(p, c) ) / ( sum_i wb_i(p) )
 *
 * where wb_i is frame i's weight map after a small box blur.
 *
 * Blurring the weight maps before normalisation is what keeps naive per-pixel fusion
 * from haloing: without it the hard, high-frequency transitions between "trust this
 * exposure" and "trust that one" imprint visible seams and reversal artefacts around
 * high-contrast edges. A separable box blur of radius [DEFAULT_BLUR_RADIUS] smears
 * the transitions into gentle ramps, approximating the low-pass role that a full
 * Laplacian-pyramid blend plays in true Mertens fusion. This is a deliberate
 * two-level simplification; multi-scale pyramid fusion (per-band blending of a
 * Laplacian pyramid of the frames against a Gaussian pyramid of the weights) is a
 * future refinement that would further suppress residual low-frequency halos.
 *
 * A tiny [WEIGHT_EPS] floor is added to every blurred weight so a pixel that is
 * clipped or crushed in every exposure (all weights 0) falls back to an equal-weight
 * average instead of dividing by zero. Output inherits the first frame's dimensions
 * and timestamp with alpha forced opaque.
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object ExposureFusion {

    /** Box-blur radius applied to each weight map before normalisation. */
    const val DEFAULT_BLUR_RADIUS = 8

    /** Floor added to every blurred weight so normalisation never divides by zero. */
    private const val WEIGHT_EPS = 1e-6

    /**
     * Fuses [frames] using their matching relative exposures [evs].
     *
     * @param frames aligned input frames, all sharing dimensions; must be non-empty.
     * @param evs relative EV per frame, parallel to [frames].
     * @param blurRadius weight-map box-blur radius; 0 disables blurring.
     */
    fun fuse(
        frames: List<Frame>,
        evs: List<Double>,
        blurRadius: Int = DEFAULT_BLUR_RADIUS,
    ): Frame {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(frames.size == evs.size) { "frames and evs must have equal size" }
        require(blurRadius >= 0) { "blurRadius must be >= 0" }
        val width = frames.first().width
        val height = frames.first().height
        require(frames.all { it.width == width && it.height == height }) {
            "all frames must share dimensions"
        }

        val pixelCount = width * height
        val weightMaps = frames.mapIndexed { index, frame ->
            val raw = ExposureFusionWeights.weightMap(frame, evs[index])
            if (blurRadius > 0) BoxBlur.blur(raw, width, height, blurRadius) else raw
        }

        // Per-pixel fuse: each output pixel sums the frames at its own index only, so
        // pixels are independent and the loop is row-parallel (the per-pixel sum over
        // frames stays in frame order, so the result is bit-identical to serial).
        val frameArgb = Array(frames.size) { frames[it].argb }
        val out = IntArray(pixelCount)
        PipelineParallel.parallelRows(pixelCount) { start, end ->
            for (p in start until end) {
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var sumW = 0.0
                for (index in frames.indices) {
                    val w = weightMaps[index][p] + WEIGHT_EPS
                    val pixel = frameArgb[index][p]
                    sumR += w * ((pixel shr 16) and 0xFF)
                    sumG += w * ((pixel shr 8) and 0xFF)
                    sumB += w * (pixel and 0xFF)
                    sumW += w
                }
                val r = (sumR / sumW).roundToInt().coerceIn(0, 255)
                val g = (sumG / sumW).roundToInt().coerceIn(0, 255)
                val b = (sumB / sumW).roundToInt().coerceIn(0, 255)
                out[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Frame(width, height, out, frames.first().timestampMillis)
    }
}
