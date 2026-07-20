package com.poc.camera.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tunable parameters for [DetailEnhancer].
 *
 * @property radius guided-filter window half-width used as the edge-aware low-pass.
 *   Small (~2-4) so the base tracks edges closely and the residual detail carries the
 *   fine texture/structure the sharpener amplifies, without the wide halo an unguided
 *   Gaussian unsharp mask produces.
 * @property eps guided-filter edge regulariser in squared 8-bit luma units. Larger
 *   than the smoothing passes ([LocalToneMapper] / [ChromaDenoiser]): sharpening wants
 *   medium texture in the detail layer, so the default (0.10 * 255)^2 folds luma swings
 *   under ~10% of full scale into the base (their residual detail is what gets
 *   amplified) while strong edges (a hard 150-code step has variance far above eps)
 *   stay in the base and carry through untouched. Residual halos on those preserved
 *   edges are caught by the overshoot clamp rather than by eps alone.
 * @property gain unsharp amount on the cored detail (>= 0). The enhanced luma is
 *   luma + gain * coring(detail), so a clean edge/texture gains ~(1 + gain) amplitude;
 *   the default ~0.6 is a firm-but-restrained sharpen.
 * @property coringSigmaFactor scales the coring knee against the detail plane's robust
 *   noise sigma: knee t = coringSigmaFactor * sigmaMAD. Larger suppresses more of the
 *   low-amplitude detail (noise) before amplifying, at the cost of softening genuine
 *   mid-amplitude texture.
 * @property overshootAllowance codes of slack (in 8-bit luma) added around the local
 *   3x3 min/max when clamping the sharpened luma, so a hard edge may sharpen right up
 *   to its neighbours' range plus this margin but not ring past it.
 */
data class DetailParams(
    val radius: Int = DEFAULT_RADIUS,
    val eps: Double = DEFAULT_EPS,
    val gain: Double = DEFAULT_GAIN,
    val coringSigmaFactor: Double = DEFAULT_CORING_SIGMA_FACTOR,
    val overshootAllowance: Double = DEFAULT_OVERSHOOT_ALLOWANCE,
) {
    init {
        require(radius >= 0) { "radius must be >= 0" }
        require(eps > 0.0) { "eps must be > 0" }
        require(gain >= 0.0) { "gain must be >= 0" }
        require(coringSigmaFactor >= 0.0) { "coringSigmaFactor must be >= 0" }
        require(overshootAllowance >= 0.0) { "overshootAllowance must be >= 0" }
    }

    companion object {
        const val DEFAULT_RADIUS = 3
        const val DEFAULT_EPS = (0.10 * 255.0) * (0.10 * 255.0)
        const val DEFAULT_GAIN = 0.6
        const val DEFAULT_CORING_SIGMA_FACTOR = 2.0
        const val DEFAULT_OVERSHOOT_ALLOWANCE = 5.0
    }
}

/**
 * Edge-masked unsharp sharpening with noise-aware coring and anti-overshoot (halo)
 * control. Works on luma only, reapplying the sharpened luma per channel by the same
 * ratio approach as [LocalToneMapper] so hue and saturation are preserved.
 *
 * The low-pass is an edge-preserving [GuidedFilter.selfGuided] rather than a Gaussian:
 *
 *   base   = guidedFilter(luma)            // small radius, edges preserved
 *   detail = luma - base                   // fine texture/structure, little edge ring
 *
 * Because the guided base carries strong edges through untouched, the detail layer
 * holds texture and fine structure with far less of the wide edge overshoot a Gaussian
 * unsharp mask injects.
 *
 * Noise discrimination -- coring. Before amplification each detail sample is passed
 * through a smooth soft-threshold ("coring") curve:
 *
 *   coring(d) = d * d^2 / (d^2 + t^2)
 *
 * This is a smooth, sign-symmetric (coring(-d) = -coring(d)) variant of the hard
 * soft-threshold sign(d) * max(0, |d| - t): for |d| << t it behaves like d * (d/t)^2
 * and collapses toward zero (low-amplitude detail, i.e. noise, is suppressed), while
 * for |d| >> t it approaches d (genuine detail passes nearly unchanged). It is C-inf
 * with no discontinuity at the knee, so there is no visible threshold seam. The knee
 * t = coringSigmaFactor * sigma scales with a robust estimate of the detail plane's
 * noise sigma (median-absolute-deviation, sigma = 1.4826 * median(|d - median d|),
 * the same MAD-to-sigma scaling [GhostRejector] uses), so a flat noisy patch -- whose
 * detail is all ~sigma -- is cored away while higher-amplitude texture survives. A
 * single uniformly noisy or uniformly textured plane cannot be told apart from one
 * frame; discrimination works where low-amplitude noise and higher-amplitude detail
 * COEXIST, which is the real-capture case.
 *
 * Amplification:
 *
 *   sharp = luma + gain * coring(detail)
 *
 * Halo/overshoot control -- the standard anti-overshoot clamp. Unsharp masking rings:
 * it pushes pixels next to a hard edge brighter than the brightest / darker than the
 * darkest neighbour, which reads as a halo. The sharpened luma is clamped into the
 * local range of the ORIGINAL luma over a 3x3 neighbourhood (a separable O(pixels)
 * erode/dilate) widened by [DetailParams.overshootAllowance] codes:
 *
 *   lo    = erode3x3(luma) - overshootAllowance
 *   hi    = dilate3x3(luma) + overshootAllowance
 *   sharp = clamp(sharp, lo, hi)
 *
 * At a genuine edge the 3x3 window spans both sides, so lo/hi cover the full step and
 * the edge sharpens freely; a pixel or two into a flat region the window is single-
 * valued, so any ringing overshoot is clamped back to the neighbourhood plus the small
 * allowance.
 *
 * Luma-preserving reapplication (as in [LocalToneMapper]): the per-channel ratio
 * sharp/luma is clamped to [[RATIO_MIN], [RATIO_MAX]] with an [RATIO_EPS] floor on both
 * terms so near-black pixels cannot blow chroma up through division.
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object DetailEnhancer {

    /** Floor added to both luma terms of the per-channel ratio (avoids /0 at black). */
    private const val RATIO_EPS = 1.0

    /** Bounds on the per-channel luma ratio, guarding chroma against blow-up. */
    private const val RATIO_MIN = 0.25
    private const val RATIO_MAX = 4.0

    // Rec. 601 luma weights, matching [Luminance] / [LocalToneMapper] / [ChromaDenoiser].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /** MAD-to-sigma scaling for Gaussian noise (matches [GhostRejector]). */
    private const val MAD_TO_SIGMA = 1.4826

    /** Applies detail enhancement to [frame], preserving its dimensions and alpha. */
    fun apply(frame: Frame, params: DetailParams = DetailParams()): Frame {
        val src = frame.argb
        val luma = DoubleArray(src.size) { i ->
            val pixel = src[i]
            R_WEIGHT * ((pixel shr 16) and 0xFF) +
                G_WEIGHT * ((pixel shr 8) and 0xFF) +
                B_WEIGHT * (pixel and 0xFF)
        }

        val base = GuidedFilter.selfGuided(luma, frame.width, frame.height, params.radius, params.eps)
        val detail = DoubleArray(src.size) { luma[it] - base[it] }
        val knee = params.coringSigmaFactor * robustSigma(detail)

        // Local range of the ORIGINAL luma for the anti-overshoot clamp.
        val lo = erode3x3(luma, frame.width, frame.height)
        val hi = dilate3x3(luma, frame.width, frame.height)

        val out = IntArray(src.size)
        for (i in src.indices) {
            val inLuma = luma[i]
            val sharp = inLuma + params.gain * coring(detail[i], knee)
            val clamped = sharp.coerceIn(lo[i] - params.overshootAllowance, hi[i] + params.overshootAllowance)
            val ratio = ((clamped + RATIO_EPS) / (inLuma + RATIO_EPS)).coerceIn(RATIO_MIN, RATIO_MAX)

            val pixel = src[i]
            val a = (pixel ushr 24) and 0xFF
            val r = (((pixel shr 16) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            val g = (((pixel shr 8) and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            val b = ((pixel and 0xFF) * ratio).roundToInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    /**
     * Smooth coring soft-threshold: d * d^2 / (d^2 + t^2). Sign-symmetric, C-inf, and
     * bounded by |d| (never amplifies on its own). Returns 0 at d == 0 (also the safe
     * value when both d and [t] are 0, avoiding a 0/0). For t == 0 it is the identity.
     */
    fun coring(d: Double, t: Double): Double {
        val d2 = d * d
        if (d2 == 0.0) return 0.0
        return d * d2 / (d2 + t * t)
    }

    /**
     * Robust noise sigma of a zero-ish-mean [detail] plane: 1.4826 * MAD, i.e.
     * 1.4826 * median(|d - median(d)|). Robust to the high-amplitude texture samples
     * that should pass coring rather than inflate the noise floor.
     */
    private fun robustSigma(detail: DoubleArray): Double {
        if (detail.isEmpty()) return 0.0
        val median = medianOf(detail)
        val deviations = DoubleArray(detail.size) { abs(detail[it] - median) }
        return MAD_TO_SIGMA * medianOf(deviations)
    }

    /** Median of a copy of [values] (input left untouched). */
    private fun medianOf(values: DoubleArray): Double {
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Separable 3x3 morphological dilation (local max), edges clamped. */
    private fun dilate3x3(src: DoubleArray, width: Int, height: Int): DoubleArray =
        morph3x3(src, width, height, dilate = true)

    /** Separable 3x3 morphological erosion (local min), edges clamped. */
    private fun erode3x3(src: DoubleArray, width: Int, height: Int): DoubleArray =
        morph3x3(src, width, height, dilate = false)

    /**
     * Separable 3x3 min/max (erode/dilate) with nearest-sample edge clamping. A
     * horizontal pass then a vertical pass, each O(pixels): a 3x3 min/max is the min/max
     * of the 3-wide horizontal min/max over the 3 rows.
     */
    private fun morph3x3(src: DoubleArray, width: Int, height: Int, dilate: Boolean): DoubleArray {
        val horizontal = DoubleArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left = src[row + max(0, x - 1)]
                val mid = src[row + x]
                val right = src[row + min(width - 1, x + 1)]
                horizontal[row + x] = if (dilate) max(mid, max(left, right)) else min(mid, min(left, right))
            }
        }
        val out = DoubleArray(src.size)
        for (y in 0 until height) {
            val up = max(0, y - 1) * width
            val here = y * width
            val down = min(height - 1, y + 1) * width
            for (x in 0 until width) {
                val a = horizontal[up + x]
                val b = horizontal[here + x]
                val c = horizontal[down + x]
                out[here + x] = if (dilate) max(b, max(a, c)) else min(b, min(a, c))
            }
        }
        return out
    }
}
