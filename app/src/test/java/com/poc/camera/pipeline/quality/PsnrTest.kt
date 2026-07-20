package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsnrTest {

    private fun frame(width: Int, height: Int, pixels: IntArray): Frame =
        Frame(width, height, pixels, timestampMillis = 0L)

    private fun constant(width: Int, height: Int, r: Int, g: Int, b: Int): Frame =
        frame(width, height, IntArray(width * height) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b })

    @Test
    fun identicalFramesAreInfinite() {
        val clean = SyntheticScenes.clean("texture")
        assertEquals(Double.POSITIVE_INFINITY, Psnr.between(clean, clean), 0.0)
    }

    @Test
    fun knownConstantOffsetMatchesHandComputedValue() {
        // Every pixel differs by exactly 10 on the red channel only. MSE over the
        // three channels is (10^2)/3 = 33.333..., so PSNR = 10*log10(255^2 / MSE).
        val a = constant(4, 4, 100, 100, 100)
        val b = constant(4, 4, 110, 100, 100)
        val expected = 10.0 * Math.log10(255.0 * 255.0 / (100.0 / 3.0))
        assertEquals(expected, Psnr.between(a, b), 1e-9)
    }

    @Test
    fun isSymmetric() {
        val clean = SyntheticScenes.clean("edges")
        val noisy = SyntheticScenes.noisy(clean, seed = 7L)
        assertEquals(Psnr.between(clean, noisy), Psnr.between(noisy, clean), 1e-9)
    }

    @Test
    fun addingNoiseLowersPsnrAndMoreNoiseLowersItFurther() {
        val clean = SyntheticScenes.clean("texture")
        val light = SyntheticScenes.noisy(clean, seed = 1L, readNoise = 4.0, shotGain = 0.0)
        val heavy = SyntheticScenes.noisy(clean, seed = 1L, readNoise = 16.0, shotGain = 0.0)

        val cleanPsnr = Psnr.between(clean, clean)
        val lightPsnr = Psnr.between(clean, light)
        val heavyPsnr = Psnr.between(clean, heavy)

        assertTrue("noise must lower PSNR below the infinite identity", lightPsnr < cleanPsnr)
        assertTrue("more noise must lower PSNR further", heavyPsnr < lightPsnr)
    }
}
