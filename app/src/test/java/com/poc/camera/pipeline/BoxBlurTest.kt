package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {

    /** Reference box mean with clamped edges, independent of the implementation. */
    private fun naive(src: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        val out = DoubleArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        sum += src[sy * width + sx]
                        count++
                    }
                }
                out[y * width + x] = sum / count
            }
        }
        return out
    }

    @Test
    fun flatFieldIsUnchanged() {
        val src = DoubleArray(8 * 8) { 42.0 }
        val blurred = BoxBlur.blur(src, 8, 8, 3)
        for (v in blurred) assertEquals(42.0, v, 1e-9)
    }

    @Test
    fun matchesNaiveBoxMean() {
        val width = 11
        val height = 9
        // Deterministic pseudo-random field.
        var state = 12345L
        val src = DoubleArray(width * height) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFFFF).toDouble()
        }
        for (radius in intArrayOf(0, 1, 2, 4, 7)) {
            val actual = BoxBlur.blur(src, width, height, radius)
            val expected = naive(src, width, height, radius)
            for (i in actual.indices) {
                assertTrue(
                    "radius $radius index $i: ${actual[i]} vs ${expected[i]}",
                    abs(actual[i] - expected[i]) < 1e-6,
                )
            }
        }
    }

    @Test
    fun radiusZeroReturnsACopy() {
        val src = DoubleArray(4 * 3) { it.toDouble() }
        val blurred = BoxBlur.blur(src, 4, 3, 0)
        for (i in src.indices) assertEquals(src[i], blurred[i], 0.0)
    }

    @Test
    fun negativeRadiusIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BoxBlur.blur(DoubleArray(4), 2, 2, -1)
        }
    }
}
