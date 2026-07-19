package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Pivot contrast around mid-gray (128): out = 128 + factor * (value - 128),
 * clamped to [0, 255] per channel.
 *
 * factor 1 is an exact identity, factor < 1 pulls values toward mid-gray and
 * factor > 1 pushes them away. Pure math with no Android dependencies.
 */
object Contrast {

    fun apply(frame: Frame, factor: Double): Frame {
        val src = frame.argb
        val out = IntArray(src.size)
        for (i in src.indices) {
            val pixel = src[i]
            val a = (pixel ushr 24) and 0xFF
            val r = adjust((pixel shr 16) and 0xFF, factor)
            val g = adjust((pixel shr 8) and 0xFF, factor)
            val b = adjust(pixel and 0xFF, factor)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    private fun adjust(value: Int, factor: Double): Int =
        (128 + factor * (value - 128)).roundToInt().coerceIn(0, 255)
}
