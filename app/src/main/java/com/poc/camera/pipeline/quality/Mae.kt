package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import kotlin.math.abs

/**
 * Mean absolute error between two [Frame]s: the absolute per-channel difference
 * averaged over the R, G and B channels (alpha ignored) and every pixel. This is
 * the canonical form of the error informally used in the merge tests. Symmetric
 * in its arguments. Pure math, no Android dependencies.
 */
object Mae {

    fun between(a: Frame, b: Frame): Double {
        require(a.width == b.width && a.height == b.height) {
            "frames must share dimensions: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val pa = a.argb
        val pb = b.argb
        var sum = 0L
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)).toLong()
            sum += abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)).toLong()
            sum += abs((x and 0xFF) - (y and 0xFF)).toLong()
        }
        return sum.toDouble() / (pa.size.toDouble() * 3.0)
    }
}
