package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SaturationTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (299 * r + 587 * g + 114 * b + 500) / 1000
    }

    private fun spread(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    // Mid-tone colours whose channels stay in range under a 1.5x boost, so luma
    // preservation is tested in the regime where clamping does not distort it.
    private val sample = Frame(
        width = 3,
        height = 1,
        argb = intArrayOf(argb(150, 120, 100), argb(110, 140, 130), argb(128, 128, 128)),
        timestampMillis = 5L,
    )

    @Test
    fun factorOneIsExactIdentity() {
        val out = Saturation.apply(sample, 1.0)
        assertEquals(sample.argb.toList(), out.argb.toList())
        assertEquals(5L, out.timestampMillis)
    }

    @Test
    fun factorZeroProducesGray() {
        val out = Saturation.apply(sample, 0.0)
        for (i in sample.argb.indices) {
            val expected = luma(sample.argb[i])
            val pixel = out.argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertEquals("R at $i", expected, r)
            assertEquals("G at $i", expected, g)
            assertEquals("B at $i", expected, b)
        }
    }

    @Test
    fun boostIncreasesSpreadAndPreservesLuma() {
        val boosted = Saturation.apply(sample, 1.5)
        for (i in sample.argb.indices) {
            val original = sample.argb[i]
            val out = boosted.argb[i]
            // Skip the neutral gray pixel, whose spread is already zero.
            if (spread(original) > 0) {
                assertTrue(
                    "spread should grow at $i: ${spread(original)} -> ${spread(out)}",
                    spread(out) > spread(original),
                )
            }
            assertTrue(
                "luma drifted at $i: ${luma(original)} -> ${luma(out)}",
                abs(luma(out) - luma(original)) <= 1,
            )
        }
    }
}
