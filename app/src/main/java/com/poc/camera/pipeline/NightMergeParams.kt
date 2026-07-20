package com.poc.camera.pipeline

/**
 * Tuning profile for the high-gain low-light merge, applied via the optional
 * parameter of [BurstMergePipeline.merge]. It does not replace the standard merge; it
 * widens and re-weights the same stages so a long, strongly-noisy burst stacks cleanly
 * without over-rejecting genuine signal.
 *
 * Three knobs, each justified against the "nightscene" golden (see
 * `NightGoldenRegressionTest`):
 *
 *  - [ghostLoScale]/[ghostHiScale] widen the [GhostRejector] ramp from the standard
 *    3-8 sigma to 4-12 sigma. Under heavy shot+read noise the robust per-frame sigma
 *    estimate is itself noisy and biased low, so the standard 3-8 ramp starts rejecting
 *    real, correctly-aligned pixels as if they were motion, discarding signal the merge
 *    needs. The wider ramp keeps those pixels (more frames averaged -> less residual
 *    noise) while still cutting true local motion, which sits far past 12 sigma.
 *
 *  - [motionWeightK] drives a per-frame GLOBAL weight from the frame's tile-align
 *    residual: a frame whose residual (after alignment) exceeds the burst's baseline
 *    noise carries un-cancellable motion and is down-weighted everywhere, not just where
 *    the per-pixel ghost rejector happens to fire. See [globalMotionWeight].
 *
 *  - [gainMatchEnabled] with [minGain]/[maxGain]/[trimFraction] matches each frame's
 *    trimmed-mean luma to the reference before merging (see [BrightnessNormalizer]), so
 *    AE drift across a long capture does not bias the merge weights toward the brighter
 *    or darker frames.
 */
data class NightMergeParams(
    val ghostLoScale: Double = 4.0,
    val ghostHiScale: Double = 12.0,
    val motionWeightK: Double = 1.0,
    val gainMatchEnabled: Boolean = true,
    val minGain: Double = 0.7,
    val maxGain: Double = 1.4,
    val trimFraction: Double = 0.05,
) {
    init {
        require(ghostLoScale >= 0.0) { "ghostLoScale must be >= 0" }
        require(ghostHiScale > ghostLoScale) { "ghostHiScale must exceed ghostLoScale" }
        require(motionWeightK >= 0.0) { "motionWeightK must be >= 0" }
        require(minGain > 0.0 && maxGain >= minGain) { "gain bounds must satisfy 0 < minGain <= maxGain" }
        require(trimFraction in 0.0..0.49) { "trimFraction must be in [0, 0.49]" }
    }

    /** A [GhostRejector] with this profile's widened thresholds. */
    fun ghostRejector(): GhostRejector = GhostRejector(loScale = ghostLoScale, hiScale = ghostHiScale)

    /**
     * Per-frame global merge weight in (0, 1] for a frame whose aligned residual
     * exceeds the burst's baseline noise by [residualExcess] (0 when the frame is
     * consistent with the reference up to noise), given that baseline [referenceSigma]:
     *
     *   w = 1 / (1 + k * residualExcess / referenceSigma)
     *
     * At zero excess the weight is exactly 1 (a well-aligned frame contributes fully);
     * as the excess grows the weight decays smoothly toward 0, so a frame carrying large
     * residual motion contributes little to the stack even in regions where it happens to
     * agree locally. [referenceSigma] is floored so a near-noiseless burst cannot divide
     * by zero.
     */
    fun globalMotionWeight(residualExcess: Double, referenceSigma: Double): Double {
        require(residualExcess >= 0.0) { "residualExcess must be >= 0" }
        val s = if (referenceSigma < MIN_REFERENCE_SIGMA) MIN_REFERENCE_SIGMA else referenceSigma
        return 1.0 / (1.0 + motionWeightK * (residualExcess / s))
    }

    companion object {
        /** Baseline-noise floor for [globalMotionWeight], guarding the division. */
        const val MIN_REFERENCE_SIGMA = 1.0

        /** The shipped night profile: 4-12 sigma ghost ramp, k=3 motion weighting, gain match on. */
        val NIGHT = NightMergeParams()
    }
}
