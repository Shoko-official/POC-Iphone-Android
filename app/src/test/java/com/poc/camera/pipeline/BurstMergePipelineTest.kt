package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstMergePipelineTest {

    @Test
    fun singleFrameIsReturnedUnchanged() {
        val frame = SyntheticImages.noiseFrame(8, 8, seed = 1L, timestampMillis = 42L)

        val result = BurstMergePipeline.merge(listOf(frame))

        assertSame(frame, result.merged)
        assertEquals(1, result.usedFrameCount)
        assertEquals(listOf(0 to 0), result.offsets)
    }

    @Test
    fun mergingNoisyVariantsReducesErrorBelowEverySingleFrame() {
        val width = 64
        val height = 64
        val amplitude = 20
        // Textured base image; every frame is this base plus independent noise, so
        // alignment should lock to (0, 0) and averaging should cancel the noise.
        val base = SyntheticImages.texturedCanvas(width, height, seed = 0xBA5EL)

        val frames = (0 until 6).map { i ->
            SyntheticImages.noisyVariant(base, width, height, seed = 100L + i, amplitude = amplitude)
        }

        val result = BurstMergePipeline.merge(frames)

        assertEquals(6, result.usedFrameCount)
        result.offsets.forEach { assertEquals(0 to 0, it) }

        val mergedError = SyntheticImages.meanAbsError(result.merged, base)
        val singleErrors = frames.map { SyntheticImages.meanAbsError(it, base) }

        // Real noise-reduction property: the merge is strictly closer to the clean
        // base than any individual noisy input.
        assertTrue(
            "merged MAE $mergedError should be below the best single-frame MAE ${singleErrors.min()}",
            mergedError < singleErrors.min(),
        )
    }

    @Test
    fun mergeIsDeterministic() {
        val width = 48
        val height = 48
        val base = SyntheticImages.texturedCanvas(width, height, seed = 0xD37EL)
        val frames = (0 until 6).map { i ->
            SyntheticImages.noisyVariant(base, width, height, seed = 200L + i, amplitude = 18)
        }

        val first = BurstMergePipeline.merge(frames)
        val second = BurstMergePipeline.merge(frames)

        // Byte-for-byte identical output across runs (no randomness in the pipeline).
        assertEquals(first.merged, second.merged)
        assertEquals(first.usedFrameCount, second.usedFrameCount)
        assertEquals(first.offsets, second.offsets)
    }
}
