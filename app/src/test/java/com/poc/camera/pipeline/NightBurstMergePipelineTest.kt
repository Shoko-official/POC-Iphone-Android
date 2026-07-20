package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NightBurstMergePipelineTest {

    private val burst = SyntheticScenes.nightBurst(baseSeed = 0x51EEDL, count = 8)

    @Test
    fun nightParamsProduceDifferentOutputThanStandardMerge() {
        val standard = BurstMergePipeline.merge(burst).merged
        val night = BurstMergePipeline.merge(burst, NightMergeParams.NIGHT).merged
        // Same dimensions, same reference, but the widened thresholds + gain match +
        // motion weighting change the merged pixels.
        assertEquals(standard.width, night.width)
        assertEquals(standard.height, night.height)
        assertNotEquals(standard, night)
    }

    @Test
    fun nightMergeIsDeterministic() {
        val first = BurstMergePipeline.merge(burst, NightMergeParams.NIGHT).merged
        val second = BurstMergePipeline.merge(burst, NightMergeParams.NIGHT).merged
        assertEquals(first, second)
    }

    @Test
    fun differentGhostThresholdsChangeTheMerge() {
        val narrow = BurstMergePipeline.merge(
            burst,
            NightMergeParams(ghostLoScale = 2.0, ghostHiScale = 5.0, gainMatchEnabled = false, motionWeightK = 0.0),
        ).merged
        val wide = BurstMergePipeline.merge(
            burst,
            NightMergeParams(ghostLoScale = 6.0, ghostHiScale = 18.0, gainMatchEnabled = false, motionWeightK = 0.0),
        ).merged
        assertNotEquals(narrow, wide)
    }

    @Test
    fun singleFrameNightMergeReturnsThatFrame() {
        val one = burst.take(1)
        val result = BurstMergePipeline.merge(one, NightMergeParams.NIGHT)
        assertEquals(one.first(), result.merged)
        assertEquals(1, result.usedFrameCount)
    }
}
