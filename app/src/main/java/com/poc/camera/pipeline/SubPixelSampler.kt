package com.poc.camera.pipeline

import kotlin.math.floor

/**
 * Bilinear sampling of a [Frame]'s ARGB channels at fractional coordinates, with
 * coordinates clamped to the valid pixel range at the borders. Used by
 * [RobustFrameMerger] to fetch frame pixels at the fractional offsets produced by
 * [TileAligner].
 *
 * Pure integer-indexed math with no per-call allocation ([sampleRgb] writes into a
 * caller-owned scratch array), so it is safe in the merge hot loop.
 */
object SubPixelSampler {

    /**
     * Bilinear value of one channel (0 = R, 1 = G, 2 = B) of [frame] at fractional
     * (x, y). Out-of-range coordinates clamp to the nearest edge pixel.
     */
    fun sample(frame: Frame, channel: Int, x: Double, y: Double): Double {
        val shift = shiftOf(channel)
        val width = frame.width
        val maxX = width - 1
        val maxY = frame.height - 1
        val cx = x.coerceIn(0.0, maxX.toDouble())
        val cy = y.coerceIn(0.0, maxY.toDouble())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0
        val argb = frame.argb
        val p00 = argb[y0 * width + x0]
        val p10 = argb[y0 * width + x1]
        val p01 = argb[y1 * width + x0]
        val p11 = argb[y1 * width + x1]
        return bilerp(p00, p10, p01, p11, shift, fx, fy)
    }

    /**
     * Bilinear R, G and B of [frame] at fractional (x, y) written into [out]
     * (out[0] = R, out[1] = G, out[2] = B). Shares the corner lookup across all
     * three channels; [out] must have length >= 3.
     */
    fun sampleRgb(frame: Frame, x: Double, y: Double, out: DoubleArray) {
        val width = frame.width
        val maxX = width - 1
        val maxY = frame.height - 1
        val cx = x.coerceIn(0.0, maxX.toDouble())
        val cy = y.coerceIn(0.0, maxY.toDouble())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0
        val argb = frame.argb
        val p00 = argb[y0 * width + x0]
        val p10 = argb[y0 * width + x1]
        val p01 = argb[y1 * width + x0]
        val p11 = argb[y1 * width + x1]
        out[0] = bilerp(p00, p10, p01, p11, 16, fx, fy)
        out[1] = bilerp(p00, p10, p01, p11, 8, fx, fy)
        out[2] = bilerp(p00, p10, p01, p11, 0, fx, fy)
    }

    private fun bilerp(p00: Int, p10: Int, p01: Int, p11: Int, shift: Int, fx: Double, fy: Double): Double {
        val v00 = ((p00 shr shift) and 0xFF).toDouble()
        val v10 = ((p10 shr shift) and 0xFF).toDouble()
        val v01 = ((p01 shr shift) and 0xFF).toDouble()
        val v11 = ((p11 shr shift) and 0xFF).toDouble()
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }

    private fun shiftOf(channel: Int): Int = when (channel) {
        0 -> 16
        1 -> 8
        2 -> 0
        else -> throw IllegalArgumentException("channel must be 0 (R), 1 (G) or 2 (B)")
    }
}
