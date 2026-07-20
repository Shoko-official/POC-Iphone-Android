package com.poc.camera.pipeline

import kotlin.math.floor

/**
 * Bilinear resize of a single-channel, row-major `FloatArray` plane - used to reconcile a
 * segmentation mask (issue #80) with the merged frame it will drive [BokehRenderer] on, when
 * the two don't already share dimensions.
 *
 * In practice [SubjectSegmenter][com.poc.camera.camera.SubjectSegmenter] requests MLKit's
 * default (non-raw-size) mask, which is already sized to match its input image, so this is a
 * defensive path rather than the common case. Pure Kotlin, no Android/MLKit dependency, so it
 * is covered directly by JVM unit tests with synthetic planes.
 */
object MaskResize {

    /**
     * Resizes [src] ([srcWidth] x [srcHeight], row-major) to [dstWidth] x [dstHeight] via
     * bilinear interpolation with pixel-center sampling (standard resize convention: output
     * texel centers map to `(x + 0.5) * scale - 0.5` in source space, clamped at the borders).
     * Returns a copy of [src] unchanged when the dimensions already match.
     */
    fun bilinear(src: FloatArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): FloatArray {
        require(srcWidth > 0 && srcHeight > 0) { "srcWidth and srcHeight must be > 0" }
        require(dstWidth > 0 && dstHeight > 0) { "dstWidth and dstHeight must be > 0" }
        require(src.size == srcWidth * srcHeight) {
            "src size ${src.size} must be srcWidth*srcHeight (${srcWidth * srcHeight})"
        }
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()

        val out = FloatArray(dstWidth * dstHeight)
        val scaleX = srcWidth.toDouble() / dstWidth
        val scaleY = srcHeight.toDouble() / dstHeight
        val maxX = (srcWidth - 1).toDouble()
        val maxY = (srcHeight - 1).toDouble()

        for (y in 0 until dstHeight) {
            val sy = ((y + 0.5) * scaleY - 0.5).coerceIn(0.0, maxY)
            val y0 = floor(sy).toInt()
            val y1 = if (y0 < srcHeight - 1) y0 + 1 else y0
            val fy = sy - y0
            val rowBase = y * dstWidth
            for (x in 0 until dstWidth) {
                val sx = ((x + 0.5) * scaleX - 0.5).coerceIn(0.0, maxX)
                val x0 = floor(sx).toInt()
                val x1 = if (x0 < srcWidth - 1) x0 + 1 else x0
                val fx = sx - x0

                val v00 = src[y0 * srcWidth + x0]
                val v10 = src[y0 * srcWidth + x1]
                val v01 = src[y1 * srcWidth + x0]
                val v11 = src[y1 * srcWidth + x1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[rowBase + x] = (top + (bottom - top) * fy).toFloat()
            }
        }
        return out
    }
}
