package com.poc.camera.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSubjectDecisionTest {

    @Test
    fun emptyMaskHasNoSubject() {
        assertTrue(NoSubjectDecision.hasNoSubject(FloatArray(0)))
    }

    @Test
    fun allBackgroundHasNoSubject() {
        val mask = FloatArray(100) { 0.1f }
        assertTrue(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun clearSubjectIsDetected() {
        // Half the frame confidently foreground: well above the 2% floor.
        val mask = FloatArray(100) { if (it < 50) 0.9f else 0.05f }
        assertFalse(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun justBelowMinFractionIsNoSubject() {
        val size = 1000
        // 19 confident pixels out of 1000 = 1.9%, under the 2% floor.
        val mask = FloatArray(size) { if (it < 19) 0.9f else 0.1f }
        assertTrue(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun justAboveMinFractionHasSubject() {
        val size = 1000
        // 21 confident pixels out of 1000 = 2.1%, over the 2% floor.
        val mask = FloatArray(size) { if (it < 21) 0.9f else 0.1f }
        assertFalse(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun confidenceThresholdIsInclusive() {
        val mask = FloatArray(100) { NoSubjectDecision.CONFIDENCE_THRESHOLD }
        assertFalse(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun justBelowConfidenceThresholdDoesNotCount() {
        val mask = FloatArray(100) { NoSubjectDecision.CONFIDENCE_THRESHOLD - 0.01f }
        assertTrue(NoSubjectDecision.hasNoSubject(mask))
    }

    @Test
    fun customThresholdsAreHonoured() {
        val mask = FloatArray(10) { 0.3f }
        // At the default 0.5 confidence threshold none of these pixels qualify.
        assertTrue(NoSubjectDecision.hasNoSubject(mask, confidenceThreshold = 0.5f))
        // Lowering the threshold to 0.2 makes every pixel qualify.
        assertFalse(NoSubjectDecision.hasNoSubject(mask, confidenceThreshold = 0.2f, minSubjectFraction = 0.5f))
    }
}
