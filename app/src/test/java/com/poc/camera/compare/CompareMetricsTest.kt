package com.poc.camera.compare

import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareMetricsTest {

    private fun frame(width: Int, height: Int, pixels: IntArray): Frame =
        Frame(width, height, pixels, timestampMillis = 0L)

    private fun constant(width: Int, height: Int, r: Int, g: Int, b: Int): Frame =
        frame(width, height, IntArray(width * height) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b })

    @Test
    fun identicalFramesAreFullyComparable() {
        val clean = SyntheticScenes.clean("texture")

        val result = CompareMetrics.compute(clean, clean)

        assertNotNull(result)
        assertEquals(Double.POSITIVE_INFINITY, result!!.psnrDb, 0.0)
        assertEquals(1.0, result.ssim, 1e-9)
        assertEquals(0.0, result.mae, 0.0)
    }

    @Test
    fun differingDimensionsAreNotComparable() {
        val processed = constant(16, 16, 100, 100, 100)
        val reference = constant(32, 16, 100, 100, 100)

        assertNull(CompareMetrics.compute(processed, reference))
    }

    @Test
    fun framesBelowTheMinimumSsimWindowAreNotComparable() {
        val processed = constant(4, 4, 50, 50, 50)
        val reference = constant(4, 4, 50, 50, 50)

        assertNull(CompareMetrics.compute(processed, reference))
    }

    @Test
    fun noisierReferenceLowersPsnrAndSsimButStillReturnsAResult() {
        val clean = SyntheticScenes.clean("edges")
        val noisy = SyntheticScenes.noisy(clean, seed = 3L)

        val result = CompareMetrics.compute(clean, noisy)

        assertNotNull(result)
        assertTrue("noise must lower PSNR below the identical-frame case", result!!.psnrDb < Double.POSITIVE_INFINITY)
        assertTrue("noise must lower SSIM below 1.0", result.ssim < 1.0)
        assertTrue("noise must raise MAE above 0", result.mae > 0.0)
    }
}
