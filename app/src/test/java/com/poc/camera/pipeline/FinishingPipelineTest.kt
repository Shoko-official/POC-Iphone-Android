package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishingPipelineTest {

    /** A natural-ish frame: smooth texture from [SyntheticImages] tinted per channel. */
    private fun naturalFrame(): Frame {
        val width = 48
        val height = 32
        val canvas = SyntheticImages.texturedCanvas(width + 8, height + 8, seed = 4242L)
        val gray = SyntheticImages.crop(canvas, width + 8, 2, 2, width, height, timestampMillis = 99L)
        // Break the gray symmetry so saturation and the tone curve both have work
        // to do: offset each channel by a fixed, deterministic amount.
        val argb = IntArray(gray.argb.size) { i ->
            val v = gray.argb[i] and 0xFF
            val r = (v + 25).coerceIn(0, 255)
            val g = v
            val b = (v - 25).coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(width, height, argb, gray.timestampMillis)
    }

    @Test
    fun defaultFinishingChangesTheImage() {
        val input = naturalFrame()
        val output = FinishingPipeline.apply(input)

        assertFalse("finishing must not be a no-op", output.argb.contentEquals(input.argb))
        assertEquals(input.width, output.width)
        assertEquals(input.height, output.height)
        assertEquals(input.timestampMillis, output.timestampMillis)
    }

    @Test
    fun outputAlphaIsForcedOpaque() {
        // Input carries a semi-transparent pixel; the finished frame must be opaque.
        val input = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(
                (0x80 shl 24) or (100 shl 16) or (150 shl 8) or 200,
                (0x00 shl 24) or (10 shl 16) or (20 shl 8) or 30,
            ),
            timestampMillis = 1L,
        )

        val output = FinishingPipeline.apply(input)

        for (pixel in output.argb) {
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
        }
    }

    @Test
    fun pipelineIsDeterministic() {
        val input = naturalFrame()
        val first = FinishingPipeline.apply(input)
        val second = FinishingPipeline.apply(input)

        assertTrue("same input must yield identical output", first.argb.contentEquals(second.argb))
        assertEquals(first.timestampMillis, second.timestampMillis)
    }
}
