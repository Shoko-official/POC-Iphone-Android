package com.poc.camera.pipeline

/**
 * Luma-plane extraction and 2x box downsampling used by [FrameAligner].
 *
 * Luma uses Rec. 601 weights (0.299 R, 0.587 G, 0.114 B); downsampling averages
 * 2x2 blocks. Pure integer math with no Android dependencies, so it is exercised
 * directly in JVM unit tests.
 */
object Luminance {

    fun extract(frame: Frame): LumaPlane {
        val argb = frame.argb
        val values = IntArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 luma, fixed-point weights rounded to the nearest integer.
            values[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
        }
        return LumaPlane(frame.width, frame.height, values)
    }

    /**
     * Averages each 2x2 block into one output pixel, halving both dimensions. A
     * trailing odd row/column is dropped. Requires at least a 2x2 input.
     */
    fun downsample2x(plane: LumaPlane): LumaPlane {
        val outWidth = plane.width / 2
        val outHeight = plane.height / 2
        require(outWidth > 0 && outHeight > 0) {
            "cannot downsample ${plane.width}x${plane.height}"
        }
        val src = plane.values
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val srcRow0 = (2 * y) * plane.width
            val srcRow1 = (2 * y + 1) * plane.width
            for (x in 0 until outWidth) {
                val c0 = 2 * x
                val c1 = 2 * x + 1
                val sum = src[srcRow0 + c0] + src[srcRow0 + c1] +
                    src[srcRow1 + c0] + src[srcRow1 + c1]
                out[y * outWidth + x] = (sum + 2) / 4
            }
        }
        return LumaPlane(outWidth, outHeight, out)
    }
}
