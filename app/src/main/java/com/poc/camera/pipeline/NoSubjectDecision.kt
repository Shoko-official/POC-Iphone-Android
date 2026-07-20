package com.poc.camera.pipeline

/**
 * Decides whether a segmentation soft mask (issue #80) actually found a subject, so Portrait
 * mode can fall back to a normal finished photo instead of running [BokehRenderer] on a mask
 * that is really just noise.
 *
 * "No subject" is defined as: fewer than [MIN_SUBJECT_FRACTION] of the mask's pixels are
 * confidently foreground (at or above [CONFIDENCE_THRESHOLD]). This tolerates a small subject
 * far from camera or partially out of frame while still rejecting an empty/background-only
 * scene, where MLKit's selfie segmenter tends to return a low, diffuse confidence everywhere
 * rather than a clean all-zero mask.
 *
 * Pure Kotlin, no Android/MLKit dependency, so it is directly unit-testable with synthetic
 * confidence arrays.
 */
object NoSubjectDecision {

    const val CONFIDENCE_THRESHOLD = 0.5f
    const val MIN_SUBJECT_FRACTION = 0.02f

    /**
     * True when [mask] (values in [0, 1], row-major, one per pixel) does not clear the
     * confident-subject-pixel fraction, or is empty.
     */
    fun hasNoSubject(
        mask: FloatArray,
        confidenceThreshold: Float = CONFIDENCE_THRESHOLD,
        minSubjectFraction: Float = MIN_SUBJECT_FRACTION,
    ): Boolean {
        if (mask.isEmpty()) return true
        var confidentCount = 0
        for (value in mask) {
            if (value >= confidenceThreshold) confidentCount++
        }
        val fraction = confidentCount.toFloat() / mask.size
        return fraction < minSubjectFraction
    }
}
