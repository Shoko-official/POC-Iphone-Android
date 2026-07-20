package com.poc.camera.pipeline

import kotlin.math.max

/**
 * Self-guided edge-preserving smoothing (He, Sun, Tang 2013) on a single scalar
 * plane, used by [LocalToneMapper] to split luma into a base (large-scale) layer and
 * a detail (residual) layer without the halos an unguided blur produces.
 *
 * For a plane I that guides itself, over each (2*radius+1) window:
 *
 *   meanI  = boxMean(I)
 *   meanII = boxMean(I*I)
 *   varI   = meanII - meanI^2
 *   a      = varI / (varI + eps)     // -> 1 at strong edges, -> 0 in flat regions
 *   b      = meanI * (1 - a)
 *   base   = boxMean(a) * I + boxMean(b)
 *
 * Where the window is flat (varI << eps) a -> 0, so base collapses to the local mean
 * (strong smoothing). Where the window straddles an edge (varI >> eps) a -> 1, so
 * base -> I and the edge is carried through untouched: that edge preservation is
 * exactly what stops the base/detail split from ringing, and is why [eps] behaves as
 * an intensity-domain "how flat counts as flat" threshold rather than a spatial one.
 *
 * All box means come from the shared [BoxBlur] (O(pixels) separable running sums).
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object GuidedFilter {

    /**
     * Returns the self-guided base layer of [image] (dimensions [width]x[height]).
     *
     * @param radius smoothing window half-width; larger keeps more content in detail.
     * @param eps edge-preservation regulariser in squared intensity units; larger
     *   treats bigger local swings as "flat" and smooths through them.
     */
    fun selfGuided(
        image: DoubleArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Double,
    ): DoubleArray {
        require(radius >= 0) { "radius must be >= 0" }
        require(eps > 0.0) { "eps must be > 0" }

        val meanI = BoxBlur.blur(image, width, height, radius)
        val squares = DoubleArray(image.size) { image[it] * image[it] }
        val meanII = BoxBlur.blur(squares, width, height, radius)

        val a = DoubleArray(image.size)
        val b = DoubleArray(image.size)
        for (i in image.indices) {
            // Clamp variance to >= 0: it is non-negative in exact arithmetic, but
            // meanII - meanI^2 can go slightly negative from rounding on flat runs.
            val varI = max(0.0, meanII[i] - meanI[i] * meanI[i])
            val ai = varI / (varI + eps)
            a[i] = ai
            b[i] = meanI[i] * (1.0 - ai)
        }

        val meanA = BoxBlur.blur(a, width, height, radius)
        val meanB = BoxBlur.blur(b, width, height, radius)
        return DoubleArray(image.size) { meanA[it] * image[it] + meanB[it] }
    }
}
