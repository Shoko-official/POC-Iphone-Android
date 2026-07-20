package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Luma-preserving saturation adjustment: each channel is pushed away from (or
 * pulled toward) the pixel's Rec. 601 luma by [factor].
 *
 * factor 0 collapses every pixel to gray (R = G = B = luma), factor 1 is an
 * exact identity, and factor > 1 boosts colour while leaving luma unchanged
 * within rounding. Pure math with no Android dependencies.
 *
 * An optional per-pixel [modulation] plane (see [apply]) scales the EFFECT of the
 * adjustment toward identity, used by [FinishingPipeline] for skin-tone protection.
 */
object Saturation {

    /**
     * Saturates [frame] by [factor]. When [modulation] is non-null it is a per-pixel plane
     * in [0, 1] (row-major, one entry per pixel): the styled channel is linearly interpolated
     * back toward the INPUT channel by `out = in + modulation * (styled - in)`, so
     * modulation 1 reproduces the un-modulated result EXACTLY (bit for bit) and lower values
     * apply proportionally less saturation. This is the uniform per-pixel strength control
     * [SkinMask] drives to limit saturation inside skin regions; passing null keeps the
     * original global behaviour with no extra work.
     */
    fun apply(frame: Frame, factor: Double, modulation: FloatArray? = null): Frame {
        val src = frame.argb
        val out = IntArray(src.size)
        // Per-pixel, element-wise: row-parallel.
        PipelineParallel.parallelRows(src.size) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val a = (pixel ushr 24) and 0xFF
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Rec. 601 luma, matching Luminance.extract.
                val luma = (299 * r + 587 * g + 114 * b + 500) / 1000
                // Styled (full-strength) channels in the continuous domain.
                val sr = luma + factor * (r - luma)
                val sg = luma + factor * (g - luma)
                val sb = luma + factor * (b - luma)
                val nr: Int
                val ng: Int
                val nb: Int
                if (modulation == null) {
                    nr = sr.roundToInt().coerceIn(0, 255)
                    ng = sg.roundToInt().coerceIn(0, 255)
                    nb = sb.roundToInt().coerceIn(0, 255)
                } else {
                    // Lerp the styled result back toward the input; m == 1 is exactly the
                    // un-modulated value (r + (sr - r) = sr), so a null-vs-m==1 comparison is
                    // bit-identical. Rounded once, after the lerp.
                    val m = modulation[i].toDouble()
                    nr = (r + m * (sr - r)).roundToInt().coerceIn(0, 255)
                    ng = (g + m * (sg - g)).roundToInt().coerceIn(0, 255)
                    nb = (b + m * (sb - b)).roundToInt().coerceIn(0, 255)
                }
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
