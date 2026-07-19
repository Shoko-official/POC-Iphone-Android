package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun frameOf(vararg pixels: Int): Frame =
        Frame(width = pixels.size, height = 1, argb = pixels, timestampMillis = 3L)

    private fun Frame.channels(index: Int): Triple<Int, Int, Int> {
        val pixel = argb[index]
        return Triple((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
    }

    @Test
    fun factorOneIsExactIdentity() {
        val frame = frameOf(argb(10, 128, 240), argb(60, 200, 5))
        val out = Contrast.apply(frame, 1.0)
        assertEquals(frame.argb.toList(), out.argb.toList())
        assertEquals(3L, out.timestampMillis)
    }

    @Test
    fun reducingContrastPullsTowardMidGray() {
        // 200 -> 128 + 0.5*(200-128) = 164; 60 -> 128 + 0.5*(60-128) = 94.
        val frame = frameOf(argb(200, 60, 128))
        val out = Contrast.apply(frame, 0.5)
        assertEquals(Triple(164, 94, 128), out.channels(0))
    }

    @Test
    fun increasingContrastPushesFromMidGray() {
        // 200 -> 128 + 1.5*(200-128) = 236; 60 -> 128 + 1.5*(60-128) = 26.
        val frame = frameOf(argb(200, 60, 128))
        val out = Contrast.apply(frame, 1.5)
        assertEquals(Triple(236, 26, 128), out.channels(0))
    }

    @Test
    fun extremesAreClampedToRange() {
        // 250 -> 128 + 3*(122) = 494 -> 255; 5 -> 128 + 3*(-123) = -241 -> 0.
        val frame = frameOf(argb(250, 5, 128))
        val out = Contrast.apply(frame, 3.0)
        val (r, g, b) = out.channels(0)
        assertEquals(255, r)
        assertEquals(0, g)
        assertEquals(128, b)
        assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
    }
}
