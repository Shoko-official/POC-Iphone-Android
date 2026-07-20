package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NightMergeParamsTest {

    @Test
    fun globalMotionWeightIsOneAtZeroResidual() {
        val params = NightMergeParams(motionWeightK = 3.0)
        assertEquals(1.0, params.globalMotionWeight(0.0, 10.0), 0.0)
    }

    @Test
    fun globalMotionWeightApproachesZeroFloorForLargeResidual() {
        val params = NightMergeParams(motionWeightK = 3.0)
        val small = params.globalMotionWeight(1000.0, 10.0)
        assertTrue("weight $small should approach 0 for a huge residual", small < 0.01)
        assertTrue("weight $small must stay positive", small > 0.0)
    }

    @Test
    fun globalMotionWeightDecreasesMonotonicallyWithResidual() {
        val params = NightMergeParams(motionWeightK = 2.0)
        val w1 = params.globalMotionWeight(5.0, 10.0)
        val w2 = params.globalMotionWeight(20.0, 10.0)
        val w3 = params.globalMotionWeight(50.0, 10.0)
        assertTrue("weights must strictly decrease: $w1 > $w2 > $w3", w1 > w2 && w2 > w3)
        // k=2, excess=referenceSigma -> 1/(1+2) = 1/3.
        assertEquals(1.0 / 3.0, params.globalMotionWeight(10.0, 10.0), 1e-9)
    }

    @Test
    fun globalMotionWeightWithZeroKIsAlwaysOne() {
        val params = NightMergeParams(motionWeightK = 0.0)
        assertEquals(1.0, params.globalMotionWeight(0.0, 10.0), 0.0)
        assertEquals(1.0, params.globalMotionWeight(999.0, 10.0), 0.0)
    }

    @Test
    fun globalMotionWeightFloorsReferenceSigmaToAvoidDivisionByZero() {
        val params = NightMergeParams(motionWeightK = 1.0)
        // referenceSigma below MIN_REFERENCE_SIGMA is floored, so the call is finite.
        val w = params.globalMotionWeight(1.0, 0.0)
        assertTrue("weight $w must be finite and in (0,1]", w > 0.0 && w <= 1.0)
        // With the floor (1.0): 1/(1 + 1*1/1) = 0.5.
        assertEquals(0.5, w, 1e-9)
    }

    @Test
    fun ghostRejectorUsesWidenedThresholds() {
        val params = NightMergeParams(ghostLoScale = 4.0, ghostHiScale = 12.0)
        val rejector = params.ghostRejector()
        // At sigma 1, a diff of 10 sigma is fully rejected by the standard 3-8 ramp but
        // still partially kept by the widened 4-12 ramp.
        assertTrue("widened ramp must keep a 10-sigma diff", rejector.weight(10.0, 1.0) > 0.0)
        assertEquals(0.0, GhostRejector().weight(10.0, 1.0), 0.0)
    }

    @Test
    fun invalidThresholdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NightMergeParams(ghostLoScale = 5.0, ghostHiScale = 4.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NightMergeParams(motionWeightK = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NightMergeParams(minGain = 1.5, maxGain = 1.0)
        }
    }
}
