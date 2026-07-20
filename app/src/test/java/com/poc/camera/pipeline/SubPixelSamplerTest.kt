package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class SubPixelSamplerTest {

    private val width = 4
    private val height = 4

    // Separable-linear channels, which bilinear interpolation reproduces exactly:
    //   R = 10x + 5y + 3, G = 2x + 3y + 1, B = x + y + 7.
    private val frame: Frame = Frame(
        width,
        height,
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val r = 10 * x + 5 * y + 3
            val g = 2 * x + 3 * y + 1
            val b = x + y + 7
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        },
        timestampMillis = 0L,
    )

    @Test
    fun samplesExactlyAtIntegerCoordinates() {
        assertEquals(10.0 * 2 + 5 * 3 + 3, SubPixelSampler.sample(frame, 0, 2.0, 3.0), 1e-9)
    }

    @Test
    fun bilinearIsExactForSeparableLinearData() {
        // R at (1.5, 2.0) = 10*1.5 + 5*2 + 3 = 28.
        assertEquals(28.0, SubPixelSampler.sample(frame, 0, 1.5, 2.0), 1e-9)
        // R at (1.5, 2.5) = 15 + 12.5 + 3 = 30.5.
        assertEquals(30.5, SubPixelSampler.sample(frame, 0, 1.5, 2.5), 1e-9)
        // G at (0.25, 1.75) = 2*0.25 + 3*1.75 + 1 = 6.75.
        assertEquals(6.75, SubPixelSampler.sample(frame, 1, 0.25, 1.75), 1e-9)
    }

    @Test
    fun clampsCoordinatesAtBorders() {
        // Negative coords clamp to the (0, 0) pixel: R = 3.
        assertEquals(3.0, SubPixelSampler.sample(frame, 0, -5.0, -2.0), 1e-9)
        // Beyond the right/bottom edge clamps to (3, 3): R = 30 + 15 + 3 = 48.
        assertEquals(48.0, SubPixelSampler.sample(frame, 0, 99.0, 99.0), 1e-9)
    }

    @Test
    fun sampleRgbMatchesPerChannelSampling() {
        val out = DoubleArray(3)
        SubPixelSampler.sampleRgb(frame, 2.3, 1.6, out)
        assertEquals(SubPixelSampler.sample(frame, 0, 2.3, 1.6), out[0], 1e-9)
        assertEquals(SubPixelSampler.sample(frame, 1, 2.3, 1.6), out[1], 1e-9)
        assertEquals(SubPixelSampler.sample(frame, 2, 2.3, 1.6), out[2], 1e-9)
    }
}
