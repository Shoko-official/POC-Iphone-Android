package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMirrorTest {

    private fun frameOf(width: Int, height: Int, timestampMillis: Long = 42L): Frame {
        val pixels = IntArray(width * height) { i -> (0xFF shl 24) or i }
        return Frame(width, height, pixels, timestampMillis)
    }

    @Test
    fun reversesEachRowIndependently() {
        // 3x2: row 0 = [0, 1, 2], row 1 = [3, 4, 5] -> mirrored rows [2, 1, 0], [5, 4, 3].
        val frame = frameOf(width = 3, height = 2)
        val out = frame.mirrorHorizontal()
        assertEquals(listOf(2, 1, 0, 5, 4, 3).map { (0xFF shl 24) or it }, out.argb.toList())
    }

    @Test
    fun pixelXyMapsToWidthMinusOneMinusXy() {
        val width = 5
        val height = 4
        val frame = frameOf(width, height)
        val out = frame.mirrorHorizontal()
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals(
                    "pixel ($x, $y)",
                    frame.argb[y * width + x],
                    out.argb[y * width + (width - 1 - x)],
                )
            }
        }
    }

    @Test
    fun isAnInvolution() {
        val frame = frameOf(width = 7, height = 5)
        val twice = frame.mirrorHorizontal().mirrorHorizontal()
        assertEquals(frame, twice)
    }

    @Test
    fun handlesOddWidth() {
        // Odd width: the middle column is its own mirror partner and must be unchanged.
        val frame = frameOf(width = 5, height = 1)
        val out = frame.mirrorHorizontal()
        assertEquals(frame.argb[2], out.argb[2])
        assertEquals(frame.argb[0], out.argb[4])
        assertEquals(frame.argb[4], out.argb[0])
    }

    @Test
    fun handlesEvenWidth() {
        val frame = frameOf(width = 4, height = 1)
        val out = frame.mirrorHorizontal()
        assertEquals(listOf(3, 2, 1, 0).map { (0xFF shl 24) or it }, out.argb.toList())
    }

    @Test
    fun handlesSingleColumnWidthAsIdentity() {
        val frame = frameOf(width = 1, height = 4)
        val out = frame.mirrorHorizontal()
        assertEquals(frame, out)
    }

    @Test
    fun preservesDimensionsAndTimestamp() {
        val frame = frameOf(width = 6, height = 3, timestampMillis = 9_001L)
        val out = frame.mirrorHorizontal()
        assertEquals(frame.width, out.width)
        assertEquals(frame.height, out.height)
        assertEquals(frame.timestampMillis, out.timestampMillis)
        assertEquals(frame.argb.size, out.argb.size)
    }

    @Test
    fun doesNotMutateTheInputArray() {
        val frame = frameOf(width = 4, height = 2)
        val original = frame.argb.copyOf()
        frame.mirrorHorizontal()
        assertEquals(original.toList(), frame.argb.toList())
    }
}
