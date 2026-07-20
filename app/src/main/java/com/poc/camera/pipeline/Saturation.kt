package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Luma-preserving saturation adjustment: each channel is pushed away from (or
 * pulled toward) the pixel's Rec. 601 luma by [factor].
 *
 * factor 0 collapses every pixel to gray (R = G = B = luma), factor 1 is an
 * exact identity, and factor > 1 boosts colour while leaving luma unchanged
 * within rounding. Pure math with no Android dependencies.
 */
object Saturation {

    fun apply(frame: Frame, factor: Double): Frame {
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
                val nr = (luma + factor * (r - luma)).roundToInt().coerceIn(0, 255)
                val ng = (luma + factor * (g - luma)).roundToInt().coerceIn(0, 255)
                val nb = (luma + factor * (b - luma)).roundToInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
