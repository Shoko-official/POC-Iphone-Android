package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.Mae
import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrMergePipelineTest {

    @Test
    fun groupsByEvAndFusesToReferenceDimensions() {
        val base = SyntheticImages.texturedCanvas(48, 48, seed = 0xA11L)
        // Two frames per EV at [-2, 0, 2]; slight per-frame noise, no motion.
        val frames = ArrayList<Frame>()
        val evs = ArrayList<Double>()
        var seed = 0L
        for (ev in listOf(-2.0, 0.0, 2.0)) {
            repeat(2) {
                frames.add(SyntheticImages.noisyVariant(base, 48, 48, seed = seed++, amplitude = 6))
                evs.add(ev)
            }
        }

        val result = HdrMergePipeline.merge(frames, evs)

        assertEquals(48, result.fused.width)
        assertEquals(48, result.fused.height)
        assertEquals(3, result.perEvMerged.size)
        assertEquals(listOf(-2.0, 0.0, 2.0), result.perEvMerged.map { it.ev })
        assertEquals(6, result.usedFrameCount)
    }

    @Test
    fun singleExposureDegeneratesToThatExposure() {
        val base = SyntheticImages.texturedCanvas(32, 32, seed = 0xB22L)
        val frames = (0 until 3).map { SyntheticImages.noisyVariant(base, 32, 32, seed = it.toLong(), amplitude = 5) }
        val evs = listOf(0.0, 0.0, 0.0)

        val result = HdrMergePipeline.merge(frames, evs)

        assertEquals(1, result.perEvMerged.size)
        // A single EV group is merged then fused alone, so it stays close to the base.
        assertTrue(SyntheticImages.meanAbsError(result.fused, base) < 5.0)
    }

    @Test
    fun mergeIsDeterministic() {
        val burst = SyntheticScenes.hdrBurst(0xBEEFL)

        val first = HdrMergePipeline.merge(burst.frames, burst.evs)
        val second = HdrMergePipeline.merge(burst.frames, burst.evs)

        assertEquals(first.fused, second.fused)
        assertEquals(first.usedFrameCount, second.usedFrameCount)
    }

    @Test
    fun fusionBeatsEverySingleExposureAcrossFullFrame() {
        // The core HDR property at full-frame scale: because each single exposure
        // clips or crushes one end of the range, the fused result is closer to the
        // tone-mapped truth than any single merged exposure.
        val truth = SyntheticScenes.hdrToneMappedTruth()
        val burst = SyntheticScenes.hdrBurst(0xC0FFEEL)

        val result = HdrMergePipeline.merge(burst.frames, burst.evs)

        val fusedMae = Mae.between(result.fused, truth)
        val bestSingleMae = result.perEvMerged.minOf { Mae.between(it.merged, truth) }
        assertTrue(
            "fused MAE $fusedMae must beat best single-exposure MAE $bestSingleMae",
            fusedMae < bestSingleMae,
        )
    }

    @Test
    fun mismatchedFrameAndEvCountsAreRejected() {
        val frame = SyntheticImages.noiseFrame(8, 8, seed = 1L)
        assertThrows(IllegalArgumentException::class.java) {
            HdrMergePipeline.merge(listOf(frame, frame), listOf(0.0))
        }
    }
}
