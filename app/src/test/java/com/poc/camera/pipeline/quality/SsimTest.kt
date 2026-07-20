package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsimTest {

    private fun constant(width: Int, height: Int, value: Int): Frame =
        Frame(width, height, IntArray(width * height) { (0xFF shl 24) or (value shl 16) or (value shl 8) or value }, 0L)

    @Test
    fun identicalFramesScoreExactlyOne() {
        val clean = SyntheticScenes.clean("texture")
        assertEquals(1.0, Ssim.between(clean, clean), 1e-12)
    }

    @Test
    fun identicalFlatFramesScoreOne() {
        // A degenerate flat window (zero variance) must still resolve to 1.0 via the
        // stabilising constants, not NaN.
        val flat = constant(16, 16, 128)
        assertEquals(1.0, Ssim.between(flat, flat), 1e-12)
    }

    @Test
    fun isSymmetric() {
        val clean = SyntheticScenes.clean("edges")
        val noisy = SyntheticScenes.noisy(clean, seed = 11L)
        assertEquals(Ssim.between(clean, noisy), Ssim.between(noisy, clean), 1e-12)
    }

    @Test
    fun scoreStaysInUnitRangeForTypicalInputs() {
        val clean = SyntheticScenes.clean("gradients")
        val noisy = SyntheticScenes.noisy(clean, seed = 13L)
        val score = Ssim.between(clean, noisy)
        assertTrue("SSIM must be <= 1", score <= 1.0)
        assertTrue("SSIM must be >= 0 for correlated inputs", score >= 0.0)
    }

    @Test
    fun addingNoiseLowersSsimAndMoreNoiseLowersItFurther() {
        val clean = SyntheticScenes.clean("texture")
        val light = SyntheticScenes.noisy(clean, seed = 2L, readNoise = 4.0, shotGain = 0.0)
        val heavy = SyntheticScenes.noisy(clean, seed = 2L, readNoise = 16.0, shotGain = 0.0)

        val cleanSsim = Ssim.between(clean, clean)
        val lightSsim = Ssim.between(clean, light)
        val heavySsim = Ssim.between(clean, heavy)

        assertTrue("noise must lower SSIM below 1.0", lightSsim < cleanSsim)
        assertTrue("more noise must lower SSIM further", heavySsim < lightSsim)
    }
}
