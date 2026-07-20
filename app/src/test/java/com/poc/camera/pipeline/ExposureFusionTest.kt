package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureFusionTest {

    private fun solid(width: Int, height: Int, value: Int): Frame {
        val pixel = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        return Frame(width, height, IntArray(width * height) { pixel }, 0L)
    }

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    @Test
    fun fusingIdenticalFramesReturnsThatFrame() {
        val frame = SyntheticImages.texturedCanvas(32, 32, seed = 0x1L)
            .let { Frame(32, 32, it, 0L) }

        val fused = ExposureFusion.fuse(listOf(frame, frame), listOf(0.0, 0.0))

        // Equal frames with equal weights average to (within rounding) themselves.
        for (i in frame.argb.indices) {
            assertTrue(abs(red(fused.argb[i]) - red(frame.argb[i])) <= 1)
        }
    }

    @Test
    fun fusionFavoursTheWellExposedFrame() {
        // A dark frame (value 20) and a well-exposed one (value 128); both uniform so
        // the blurred weight is uniform and the well-exposed frame dominates.
        val dark = solid(16, 16, 20)
        val bright = solid(16, 16, 128)

        val fused = ExposureFusion.fuse(listOf(dark, bright), listOf(-2.0, 0.0))

        val value = red(fused.argb[0])
        // Strictly closer to the well-exposed 128 than to the midpoint of 20 and 128.
        assertTrue("fused value $value should lean towards 128", value > (20 + 128) / 2)
    }

    @Test
    fun pixelClippedInEveryExposureFallsBackToAverageWithoutNaN() {
        // Both frames blown out and tagged over-exposed, so every weight is zeroed;
        // the epsilon floor must produce an equal-weight average, here 255.
        val a = solid(8, 8, 255)
        val b = solid(8, 8, 255)

        val fused = ExposureFusion.fuse(listOf(a, b), listOf(2.0, 2.0))

        assertTrue(fused.argb.all { red(it) == 255 })
    }

    @Test
    fun outputInheritsReferenceDimensionsAndTimestamp() {
        val a = Frame(10, 6, IntArray(60) { 0xFF808080.toInt() }, 1234L)
        val b = Frame(10, 6, IntArray(60) { 0xFF404040.toInt() }, 9999L)

        val fused = ExposureFusion.fuse(listOf(a, b), listOf(0.0, -2.0))

        assertEquals(10, fused.width)
        assertEquals(6, fused.height)
        assertEquals(1234L, fused.timestampMillis)
    }

    @Test
    fun fusionIsDeterministic() {
        val a = Frame(24, 24, SyntheticImages.texturedCanvas(24, 24, seed = 0x2L), 0L)
        val b = Frame(24, 24, SyntheticImages.texturedCanvas(24, 24, seed = 0x3L), 0L)

        val first = ExposureFusion.fuse(listOf(a, b), listOf(0.0, 2.0))
        val second = ExposureFusion.fuse(listOf(a, b), listOf(0.0, 2.0))

        assertEquals(first, second)
    }

    @Test
    fun mismatchedFrameAndEvCountsAreRejected() {
        val a = solid(4, 4, 100)
        assertThrows(IllegalArgumentException::class.java) {
            ExposureFusion.fuse(listOf(a, a), listOf(0.0))
        }
    }

    @Test
    fun mismatchedDimensionsAreRejected() {
        val a = solid(4, 4, 100)
        val b = solid(5, 4, 100)
        assertThrows(IllegalArgumentException::class.java) {
            ExposureFusion.fuse(listOf(a, b), listOf(0.0, 2.0))
        }
    }

    @Test
    fun emptyInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ExposureFusion.fuse(emptyList(), emptyList())
        }
    }
}
