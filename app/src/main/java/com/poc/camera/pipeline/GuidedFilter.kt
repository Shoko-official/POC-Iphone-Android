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
        chunkCount: Int = PipelineParallel.parallelism,
    ): DoubleArray {
        require(radius >= 0) { "radius must be >= 0" }
        require(eps > 0.0) { "eps must be > 0" }

        val meanI = BoxBlur.blur(image, width, height, radius, chunkCount)
        val squares = DoubleArray(image.size) { image[it] * image[it] }
        val meanII = BoxBlur.blur(squares, width, height, radius, chunkCount)

        // a/b are element-wise in the flat pixel index, so the row/column contract of
        // [PipelineParallel] holds directly over the pixel range.
        val a = DoubleArray(image.size)
        val b = DoubleArray(image.size)
        PipelineParallel.parallelRows(image.size, chunkCount) { start, end ->
            for (i in start until end) {
                // Clamp variance to >= 0: it is non-negative in exact arithmetic, but
                // meanII - meanI^2 can go slightly negative from rounding on flat runs.
                val varI = max(0.0, meanII[i] - meanI[i] * meanI[i])
                val ai = varI / (varI + eps)
                a[i] = ai
                b[i] = meanI[i] * (1.0 - ai)
            }
        }

        val meanA = BoxBlur.blur(a, width, height, radius, chunkCount)
        val meanB = BoxBlur.blur(b, width, height, radius, chunkCount)
        val out = DoubleArray(image.size)
        PipelineParallel.parallelRows(image.size, chunkCount) { start, end ->
            for (i in start until end) out[i] = meanA[i] * image[i] + meanB[i]
        }
        return out
    }

    /**
     * Returns the cross-guided filtering of [input] steered by a separate [guide]
     * plane (both [width]x[height]). This is the standard guided-filter estimator with
     * an independent guide, of which [selfGuided] is the special case guide == input:
     *
     *   meanI  = boxMean(input)
     *   meanG  = boxMean(guide)
     *   covIG  = boxMean(input*guide) - meanI*meanG
     *   varG   = boxMean(guide*guide) - meanG^2
     *   a      = covIG / (varG + eps)
     *   b      = meanI - a*meanG
     *   out    = boxMean(a)*guide + boxMean(b)
     *
     * The output is a locally linear function of the GUIDE, so it inherits the guide's
     * edges: where the guide is flat (varG << eps) a -> 0 and the output collapses to
     * the local mean of [input] (strong smoothing), while where the guide steps
     * (varG >> eps) the transfer preserves the transition. Used by [ChromaDenoiser] to
     * flatten chroma speckle while keeping chroma edges that co-occur with luma edges.
     *
     * @param radius smoothing window half-width.
     * @param eps regulariser in squared GUIDE-intensity units; larger treats bigger
     *   guide swings as "flat" and smooths [input] through them.
     */
    fun guided(
        input: DoubleArray,
        guide: DoubleArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Double,
        chunkCount: Int = PipelineParallel.parallelism,
    ): DoubleArray {
        require(radius >= 0) { "radius must be >= 0" }
        require(eps > 0.0) { "eps must be > 0" }
        require(input.size == guide.size) { "input and guide must have the same size" }

        val meanI = BoxBlur.blur(input, width, height, radius, chunkCount)
        val meanG = BoxBlur.blur(guide, width, height, radius, chunkCount)
        val corrIG = BoxBlur.blur(DoubleArray(input.size) { input[it] * guide[it] }, width, height, radius, chunkCount)
        val corrGG = BoxBlur.blur(DoubleArray(guide.size) { guide[it] * guide[it] }, width, height, radius, chunkCount)

        // a/b are element-wise in the flat pixel index (contract of [PipelineParallel]).
        val a = DoubleArray(input.size)
        val b = DoubleArray(input.size)
        PipelineParallel.parallelRows(input.size, chunkCount) { start, end ->
            for (i in start until end) {
                val covIG = corrIG[i] - meanI[i] * meanG[i]
                // Clamp guide variance to >= 0 (non-negative in exact arithmetic; rounding
                // on flat runs can push it slightly negative). Covariance is left signed.
                val varG = max(0.0, corrGG[i] - meanG[i] * meanG[i])
                val ai = covIG / (varG + eps)
                a[i] = ai
                b[i] = meanI[i] - ai * meanG[i]
            }
        }

        val meanA = BoxBlur.blur(a, width, height, radius, chunkCount)
        val meanB = BoxBlur.blur(b, width, height, radius, chunkCount)
        val out = DoubleArray(input.size)
        PipelineParallel.parallelRows(input.size, chunkCount) { start, end ->
            for (i in start until end) out[i] = meanA[i] * guide[i] + meanB[i]
        }
        return out
    }
}
