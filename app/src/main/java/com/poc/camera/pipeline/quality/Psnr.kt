package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import kotlin.math.log10

/**
 * Peak signal-to-noise ratio (in dB) between two [Frame]s over the RGB channels.
 *
 * Squared error is summed across the R, G and B channels (alpha ignored) and all
 * pixels, giving a mean squared error against a peak of 255; PSNR is then
 * 10*log10(255^2 / MSE). Identical frames return [Double.POSITIVE_INFINITY].
 * Symmetric in its arguments. Pure math, no Android dependencies.
 */
object Psnr {

    fun between(a: Frame, b: Frame): Double {
        require(a.width == b.width && a.height == b.height) {
            "frames must share dimensions: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val pa = a.argb
        val pb = b.argb
        var squaredError = 0L
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            val dr = ((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)
            val dg = ((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)
            val db = (x and 0xFF) - (y and 0xFF)
            squaredError += (dr * dr + dg * dg + db * db).toLong()
        }
        if (squaredError == 0L) return Double.POSITIVE_INFINITY
        val mse = squaredError.toDouble() / (pa.size.toDouble() * 3.0)
        return 10.0 * log10(255.0 * 255.0 / mse)
    }
}
