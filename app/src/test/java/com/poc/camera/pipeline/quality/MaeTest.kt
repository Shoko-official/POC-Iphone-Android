package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaeTest {

    private fun constant(width: Int, height: Int, r: Int, g: Int, b: Int): Frame =
        Frame(width, height, IntArray(width * height) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }, 0L)

    @Test
    fun identicalFramesHaveZeroError() {
        val clean = SyntheticScenes.clean("gradients")
        assertEquals(0.0, Mae.between(clean, clean), 0.0)
    }

    @Test
    fun knownConstantOffsetMatchesHandComputedValue() {
        // Channel deltas 6, 9, 3 on every pixel -> mean absolute error (6+9+3)/3 = 6.
        val a = constant(3, 2, 100, 100, 100)
        val b = constant(3, 2, 106, 109, 103)
        assertEquals(6.0, Mae.between(a, b), 1e-12)
    }

    @Test
    fun isSymmetric() {
        val clean = SyntheticScenes.clean("edges")
        val noisy = SyntheticScenes.noisy(clean, seed = 3L)
        assertEquals(Mae.between(clean, noisy), Mae.between(noisy, clean), 1e-12)
    }

    @Test
    fun addingNoiseRaisesErrorAndMoreNoiseRaisesItFurther() {
        val clean = SyntheticScenes.clean("texture")
        val light = SyntheticScenes.noisy(clean, seed = 5L, readNoise = 4.0, shotGain = 0.0)
        val heavy = SyntheticScenes.noisy(clean, seed = 5L, readNoise = 16.0, shotGain = 0.0)

        val lightMae = Mae.between(clean, light)
        val heavyMae = Mae.between(clean, heavy)

        assertTrue("noise must raise MAE above zero", lightMae > 0.0)
        assertTrue("more noise must raise MAE further", heavyMae > lightMae)
    }
}
