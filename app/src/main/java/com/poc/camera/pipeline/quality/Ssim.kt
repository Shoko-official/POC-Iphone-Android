package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame

/**
 * Single-scale structural similarity (SSIM) on Rec. 601 luminance.
 *
 * Windowing choice: 8x8 blocks with a stride of 4 (50% overlap), scored with the
 * canonical constants C1 = (0.01*255)^2 and C2 = (0.03*255)^2 and averaged over
 * all windows (the standard mean-SSIM / MSSIM index). A uniform (box) window is
 * used rather than an 11x11 Gaussian so the per-window statistics stay exact and
 * hand-verifiable; the 50%-overlapping blocks still sample local structure
 * densely. Per-window variances use the biased (divide-by-N) estimator, which is
 * conventional for SSIM and makes identical images score exactly 1.0.
 *
 * Symmetric in its arguments. Pure math, no Android dependencies.
 */
object Ssim {

    private const val WINDOW = 8
    private const val STRIDE = 4
    private const val C1 = (0.01 * 255.0) * (0.01 * 255.0)
    private const val C2 = (0.03 * 255.0) * (0.03 * 255.0)

    fun between(a: Frame, b: Frame): Double {
        require(a.width == b.width && a.height == b.height) {
            "frames must share dimensions: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        require(a.width >= WINDOW && a.height >= WINDOW) {
            "frames must be at least ${WINDOW}x$WINDOW for SSIM"
        }
        val lumaA = luma(a)
        val lumaB = luma(b)
        val width = a.width
        val height = a.height

        var total = 0.0
        var windows = 0
        var y = 0
        while (y + WINDOW <= height) {
            var x = 0
            while (x + WINDOW <= width) {
                total += windowSsim(lumaA, lumaB, width, x, y)
                windows++
                x += STRIDE
            }
            y += STRIDE
        }
        return total / windows
    }

    private fun windowSsim(
        lumaA: DoubleArray,
        lumaB: DoubleArray,
        stride: Int,
        x0: Int,
        y0: Int,
    ): Double {
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        for (dy in 0 until WINDOW) {
            val row = (y0 + dy) * stride + x0
            for (dx in 0 until WINDOW) {
                val vx = lumaA[row + dx]
                val vy = lumaB[row + dx]
                sumX += vx
                sumY += vy
                sumXX += vx * vx
                sumYY += vy * vy
                sumXY += vx * vy
            }
        }
        val n = (WINDOW * WINDOW).toDouble()
        val muX = sumX / n
        val muY = sumY / n
        val varX = sumXX / n - muX * muX
        val varY = sumYY / n - muY * muY
        val covXY = sumXY / n - muX * muY

        val numerator = (2.0 * muX * muY + C1) * (2.0 * covXY + C2)
        val denominator = (muX * muX + muY * muY + C1) * (varX + varY + C2)
        return numerator / denominator
    }

    private fun luma(frame: Frame): DoubleArray {
        val argb = frame.argb
        val out = DoubleArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            out[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        return out
    }
}
