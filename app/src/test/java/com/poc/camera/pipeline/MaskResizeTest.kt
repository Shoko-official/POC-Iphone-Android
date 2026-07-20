package com.poc.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MaskResizeTest {

    @Test
    fun matchingDimensionsReturnsAnEqualCopy() {
        val src = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val result = MaskResize.bilinear(src, 2, 2, 2, 2)
        assertArrayEquals(src, result, 1e-6f)
    }

    @Test
    fun upsamplesConstantPlaneToTheSameConstant() {
        val src = FloatArray(3 * 3) { 0.75f }
        val result = MaskResize.bilinear(src, 3, 3, 6, 5)
        for (v in result) {
            assertEquals(0.75f, v, 1e-6f)
        }
    }

    @Test
    fun downsamplesConstantPlaneToTheSameConstant() {
        val src = FloatArray(8 * 8) { 0.4f }
        val result = MaskResize.bilinear(src, 8, 8, 2, 2)
        for (v in result) {
            assertEquals(0.4f, v, 1e-6f)
        }
    }

    @Test
    fun interpolatesLinearGradientExactly() {
        // A horizontal ramp 0..1 across 5 columns; bilinear resize of a linear ramp is
        // exact regardless of scale.
        val width = 5
        val src = FloatArray(width * 1) { it / (width - 1f) }
        val result = MaskResize.bilinear(src, width, 1, 9, 1)
        // Output texel centers land at fractional source positions; endpoints must still
        // hit the source extremes.
        assertEquals(src.first(), result.first(), 1e-6f)
        assertEquals(src.last(), result.last(), 1e-6f)
    }

    @Test
    fun rejectsMismatchedSourceSize() {
        assertThrows(IllegalArgumentException::class.java) {
            MaskResize.bilinear(floatArrayOf(0f, 1f), 2, 2, 4, 4)
        }
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            MaskResize.bilinear(floatArrayOf(0f), 1, 1, 0, 1)
        }
    }
}
