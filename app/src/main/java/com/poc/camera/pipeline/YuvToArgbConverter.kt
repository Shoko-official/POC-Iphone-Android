package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Converts YUV_420_888-style plane data into a packed ARGB_8888 [IntArray], using
 * BT.601 full-range math. Pure function: planes are passed as raw [ByteArray]s with
 * their row/pixel strides rather than an Android Image/ImageProxy, so it can be
 * exercised with tiny synthetic images in JVM unit tests.
 */
object YuvToArgbConverter {

    fun convert(
        width: Int,
        height: Int,
        yBytes: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uBytes: ByteArray,
        uRowStride: Int,
        uPixelStride: Int,
        vBytes: ByteArray,
        vRowStride: Int,
        vPixelStride: Int,
    ): IntArray {
        val argb = IntArray(width * height)

        for (row in 0 until height) {
            val yRowOffset = row * yRowStride
            val chromaRowOffset = (row / 2)
            val uRowOffset = chromaRowOffset * uRowStride
            val vRowOffset = chromaRowOffset * vRowStride

            for (col in 0 until width) {
                val y = yBytes[yRowOffset + col * yPixelStride].toInt() and 0xFF
                val chromaCol = col / 2
                val u = uBytes[uRowOffset + chromaCol * uPixelStride].toInt() and 0xFF
                val v = vBytes[vRowOffset + chromaCol * vPixelStride].toInt() and 0xFF

                val r = (y + 1.402 * (v - 128)).toPixelComponent()
                val g = (y - 0.344136 * (u - 128) - 0.714136 * (v - 128)).toPixelComponent()
                val b = (y + 1.772 * (u - 128)).toPixelComponent()

                argb[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return argb
    }

    private fun Double.toPixelComponent(): Int = roundToInt().coerceIn(0, 255)
}
