package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.PipelineBenchmark
import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionTest {

    @Test
    fun outputIsExactlyDoubleTheInputDimensions() {
        val burst = SyntheticScenes.resolutionChartBurst(0x11L)
        val result = SuperResolution.superResolve(burst)
        val reference = burst.first()
        assertEquals(reference.width * SuperResolution.SCALE, result.superResolved.width)
        assertEquals(reference.height * SuperResolution.SCALE, result.superResolved.height)
        assertEquals(reference.width, result.merged.width)
        assertEquals(reference.height, result.merged.height)
    }

    @Test
    fun singleFrameDegeneratesToBilinearUpsample() {
        val frame = SyntheticScenes.clean("edges")
        val result = SuperResolution.superResolve(listOf(frame))
        assertEquals(BilinearUpsampler.upsample2x(frame), result.superResolved)
        assertEquals(1, result.usedFrameCount)
        assertEquals(frame, result.merged)
    }

    @Test
    fun repeatedRunsAreBitIdentical() {
        val burst = SyntheticScenes.resolutionChartBurst(0x2222L)
        val first = SuperResolution.superResolve(burst)
        val second = SuperResolution.superResolve(burst)
        assertEquals(first.superResolved, second.superResolved)
    }

    @Test
    fun sixFrameThreeMegapixelBurstStaysWithinCiTimeBudget() {
        // Interactive-scale tripwire: 6 frames of ~3 MP super-resolved to ~12 MP. The ceiling
        // is deliberately generous (CI machines vary wildly); it only guards against a
        // catastrophic (e.g. accidental O(n^2)) regression, not a millisecond SLA.
        val width = 2016
        val height = 1512 // 3.048 MP input -> 12.19 MP output
        val clean = PipelineBenchmark.scene(width, height, seed = 0x5000L)
        val burst = SyntheticScenes.burstOf(clean, baseSeed = 0x5000L, count = 6)

        // Warm up the JIT so the measured run is steady-state.
        SuperResolution.superResolve(SyntheticScenes.burstOf(PipelineBenchmark.scene(256, 256, 0x1L), 0x1L, 6))

        val start = System.nanoTime()
        val result = SuperResolution.superResolve(burst)
        val millis = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(width * 2, result.superResolved.width)
        assertEquals(height * 2, result.superResolved.height)
        println("SR 6x3MP -> 12MP: %.1f ms".format(millis))
        assertTrue("SR 6x3MP took ${millis} ms, above the 20 s CI tripwire", millis < 20_000.0)
    }
}
