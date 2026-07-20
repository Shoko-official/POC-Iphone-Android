package com.poc.camera.pipeline

import kotlin.math.abs

/**
 * Per-pixel, per-frame rejection weight that guards the merge against ghosting from
 * local motion. A candidate frame pixel is compared to the reference on luminance;
 * the weight is 1 when they agree, 0 when they disagree grossly, with a linear ramp
 * between so there are no hard seams around a rejected region.
 *
 * Thresholds are noise-aware: they scale with an estimated per-frame noise sigma so
 * the same rejector behaves sensibly from bright, low-noise frames to dark, noisy
 * ones. Sigma is estimated robustly (a median-absolute-deviation of the aligned
 * frame-minus-reference luma) via [estimateSigma], so a moving object -- a sparse
 * cluster of large differences -- does not inflate the noise floor and thus fails
 * the [hiScale] threshold instead of being averaged in.
 */
class GhostRejector(
    private val loScale: Double = 3.0,
    private val hiScale: Double = 8.0,
    private val minSigma: Double = 1.0,
) {
    init {
        require(loScale >= 0.0) { "loScale must be >= 0" }
        require(hiScale > loScale) { "hiScale must exceed loScale" }
        require(minSigma > 0.0) { "minSigma must be positive" }
    }

    /**
     * Rejection weight in [0, 1] for a luma [diff] (frame minus reference) given the
     * frame's noise [sigma]: 1 while |diff| <= loScale*sigma, 0 once
     * |diff| >= hiScale*sigma, linear in between.
     */
    fun weight(diff: Double, sigma: Double): Double {
        val s = if (sigma < minSigma) minSigma else sigma
        val lo = loScale * s
        val hi = hiScale * s
        val magnitude = abs(diff)
        return when {
            magnitude <= lo -> 1.0
            magnitude >= hi -> 0.0
            else -> (hi - magnitude) / (hi - lo)
        }
    }

    /**
     * Robust noise sigma from a sample of aligned frame-minus-reference luma
     * [diffs]: 1.4826 * median(|d - median(d)|), the standard MAD-to-sigma scaling
     * for Gaussian noise, floored at [minSigma]. Robust to the moving-object
     * outliers we want to reject rather than let dominate the estimate.
     */
    fun estimateSigma(diffs: DoubleArray): Double {
        if (diffs.isEmpty()) return minSigma
        val median = medianOf(diffs)
        val deviations = DoubleArray(diffs.size) { abs(diffs[it] - median) }
        val sigma = 1.4826 * medianOf(deviations)
        return if (sigma < minSigma) minSigma else sigma
    }

    /** Median of a copy of [values] (input left untouched). */
    private fun medianOf(values: DoubleArray): Double {
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
