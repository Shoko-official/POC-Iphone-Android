package com.poc.camera.pipeline

import kotlin.math.floor

/**
 * Bilinear kernel splatting onto a coverage-normalised accumulation grid, the core
 * scatter primitive of [SuperResolution].
 *
 * Where [SubPixelSampler] GATHERS a value at a fractional position, [splat] SCATTERS a
 * value INTO the four output texels straddling a fractional position, distributing it by
 * the same bilinear weights. Callers accumulate weighted colour sums and a parallel
 * weight (coverage) sum; dividing the colour sums by the weight sum at the end yields the
 * kernel-weighted mean of every sample that landed near each texel. A texel that no
 * sample reached keeps a zero weight, so the caller can detect it and fall back.
 *
 * Splats are intentionally SERIAL in [SuperResolution]: a single sample writes into four
 * neighbouring texels and adjacent samples overlap, so a naive row-parallel scatter would
 * race on shared texels and lose determinism. Kept here as a tiny, allocation-free pure
 * helper so the weight distribution can be unit-tested directly.
 */
object KernelSplat {

    /**
     * Scatters colour ([r], [g], [b]) with overall [weight] into the [width]x[height]
     * accumulators at fractional position ([x], [y]). The four texels around ([x], [y])
     * receive [weight] times their bilinear share; texels outside the grid are dropped
     * (so edge samples simply cover fewer texels, leaving the rest to the fallback).
     */
    @Suppress("LongParameterList")
    fun splat(
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
        r: Float,
        g: Float,
        b: Float,
        weight: Float,
    ) {
        if (weight <= 0f) return
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val gx = 1f - fx
        val gy = 1f - fy
        accumulate(sumR, sumG, sumB, sumW, width, height, x0, y0, gx * gy, r, g, b, weight)
        accumulate(sumR, sumG, sumB, sumW, width, height, x0 + 1, y0, fx * gy, r, g, b, weight)
        accumulate(sumR, sumG, sumB, sumW, width, height, x0, y0 + 1, gx * fy, r, g, b, weight)
        accumulate(sumR, sumG, sumB, sumW, width, height, x0 + 1, y0 + 1, fx * fy, r, g, b, weight)
    }

    @Suppress("LongParameterList")
    private fun accumulate(
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        width: Int,
        height: Int,
        px: Int,
        py: Int,
        kernel: Float,
        r: Float,
        g: Float,
        b: Float,
        weight: Float,
    ) {
        if (px < 0 || px >= width || py < 0 || py >= height) return
        val w = weight * kernel
        if (w <= 0f) return
        val idx = py * width + px
        sumR[idx] += w * r
        sumG[idx] += w * g
        sumB[idx] += w * b
        sumW[idx] += w
    }
}
