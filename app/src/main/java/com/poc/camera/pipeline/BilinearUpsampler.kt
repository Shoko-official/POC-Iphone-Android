package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Plain 2x bilinear upscale of a [Frame], with no added detail.
 *
 * Used as the fallback layer of [SuperResolution] (output texels that no burst sample
 * covered take this value) and as the single-frame degenerate SR result. It is also the
 * honest baseline the super-resolution golden proof is measured against: a bilinear
 * upsample cannot invent frequencies beyond the input Nyquist, so any resolved detail SR
 * shows above it is genuinely recovered from the burst, not interpolated.
 *
 * Output texel (x, y) samples the source at (x / 2, y / 2): even texels land exactly on a
 * source pixel and odd texels on the midpoint of its neighbours -- the same grid
 * convention [SuperResolution] splats onto (source pixel p maps to output texel 2p), so
 * the fallback and the splatted layer register pixel-for-pixel. Row-parallel and
 * deterministic (each texel is written once from read-only input).
 */
object BilinearUpsampler {

    /** The 2x-wider, 2x-taller bilinear upscale of [frame], preserving its timestamp. */
    fun upsample2x(frame: Frame): Frame {
        val outWidth = frame.width * 2
        val outHeight = frame.height * 2
        val out = IntArray(outWidth * outHeight)
        PipelineParallel.parallelRows(outHeight) { yStart, yEnd ->
            val scratch = DoubleArray(3)
            for (y in yStart until yEnd) {
                val rowBase = y * outWidth
                for (x in 0 until outWidth) {
                    SubPixelSampler.sampleRgb(frame, x / 2.0, y / 2.0, scratch)
                    val r = scratch[0].roundToInt().coerceIn(0, 255)
                    val g = scratch[1].roundToInt().coerceIn(0, 255)
                    val b = scratch[2].roundToInt().coerceIn(0, 255)
                    out[rowBase + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Frame(outWidth, outHeight, out, frame.timestampMillis)
    }
}
