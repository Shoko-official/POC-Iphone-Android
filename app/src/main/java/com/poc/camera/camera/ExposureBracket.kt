package com.poc.camera.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes the exposure-compensation plan for a bracketed HDR burst from what the
 * device reports, kept free of Android/CameraX classes so it can be unit tested
 * without Robolectric. The camera layer supplies the exposure-compensation range and
 * step (a Rational, here as numerator/denominator ints) from
 * `CameraInfo.getExposureState()`.
 *
 * CameraX exposes exposure compensation as an integer index; the actual EV applied is
 * `index * step`. To hit a target EV the nearest index is `round(targetEv / step)`,
 * clamped into the supported range. When the range cannot reach a target (e.g. a
 * device whose compensation spans less than +/-2 EV, or one with no compensation
 * support at all where the range is 0..0), the clamped index is used and the step is
 * flagged unreachable so callers can note the reduced dynamic-range gain.
 */
object ExposureBracket {

    /** Target relative EVs for the HDR bracket, darkest (highlight-preserving) first. */
    val DEFAULT_TARGET_EVS: List<Double> = listOf(-2.0, 0.0, 2.0)

    /** Frames captured per EV. */
    const val DEFAULT_FRAMES_PER_EV = 2

    /** A single bracket step: the index to apply and the EV it actually yields. */
    data class BracketStep(
        val exposureIndex: Int,
        val targetEv: Double,
        val actualEv: Double,
        val reachable: Boolean,
    )

    /**
     * Nearest supported exposure-compensation index for [targetEv], given the step as
     * [stepNumerator]/[stepDenominator] EV and the inclusive index range
     * [[rangeLower], [rangeUpper]]. A zero numerator (no compensation support) always
     * yields index 0.
     */
    fun indexForEv(
        targetEv: Double,
        stepNumerator: Int,
        stepDenominator: Int,
        rangeLower: Int,
        rangeUpper: Int,
    ): Int {
        require(stepDenominator != 0) { "stepDenominator must not be zero" }
        require(rangeLower <= rangeUpper) { "rangeLower must be <= rangeUpper" }
        if (stepNumerator == 0) return 0
        val step = stepNumerator.toDouble() / stepDenominator.toDouble()
        val raw = (targetEv / step).roundToInt()
        return raw.coerceIn(rangeLower, rangeUpper)
    }

    /**
     * Builds the ordered bracket plan for [targetEvs] (defaulting to
     * [DEFAULT_TARGET_EVS]). Each step carries the clamped index, the EV it actually
     * yields, and whether that EV is within half a step of the target.
     */
    fun plan(
        stepNumerator: Int,
        stepDenominator: Int,
        rangeLower: Int,
        rangeUpper: Int,
        targetEvs: List<Double> = DEFAULT_TARGET_EVS,
    ): List<BracketStep> {
        val step = if (stepNumerator == 0) 0.0 else stepNumerator.toDouble() / stepDenominator.toDouble()
        return targetEvs.map { target ->
            val index = indexForEv(target, stepNumerator, stepDenominator, rangeLower, rangeUpper)
            val actual = index * step
            // Reachable when the applied EV is within half a step of the target, i.e.
            // the clamp did not cut the request short.
            val reachable = abs(actual - target) <= (if (step == 0.0) 0.0 else step / 2.0) + EV_EPSILON
            BracketStep(exposureIndex = index, targetEv = target, actualEv = actual, reachable = reachable)
        }
    }

    private const val EV_EPSILON = 1e-6
}
