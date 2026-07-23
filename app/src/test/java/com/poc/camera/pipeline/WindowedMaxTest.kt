package com.poc.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowedMaxTest {

    /** Brute-force windowed max with clamped edges — the reference the fast deque must match. */
    private fun naive(src: DoubleArray, w: Int, h: Int, r: Int): DoubleArray {
        val out = DoubleArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = Double.NEGATIVE_INFINITY
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val sy = (y + dy).coerceIn(0, h - 1)
                        m = maxOf(m, src[sy * w + sx])
                    }
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    private fun field(w: Int, h: Int, seed: Long): DoubleArray {
        var s = seed and 0xFFFFFFFFL
        return DoubleArray(w * h) {
            s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((s ushr 16) and 0xFFFFL).toDouble()
        }
    }

    @Test
    fun matchesBruteForceAcrossRadii() {
        val w = 17
        val h = 13
        val src = field(w, h, 0xA11CEL)
        for (r in intArrayOf(0, 1, 2, 5, 8, 20)) {
            val fast = WindowedMax.dilate(src, w, h, r, PipelineParallel.SERIAL_CHUNKS)
            assertArrayEquals("radius $r must match brute force", naive(src, w, h, r), fast, 0.0)
        }
    }

    @Test
    fun radiusZeroIsIdentity() {
        val src = field(9, 9, 0xBEEFL)
        val out = WindowedMax.dilate(src, 9, 9, 0, PipelineParallel.SERIAL_CHUNKS)
        assertArrayEquals(src, out, 0.0)
    }

    @Test
    fun dilatesABrightSquareByRadius() {
        // A single bright pixel in a zero field dilates to a (2r+1) square of that value.
        val w = 21
        val h = 21
        val src = DoubleArray(w * h)
        src[10 * w + 10] = 100.0
        val r = 3
        val out = WindowedMax.dilate(src, w, h, r, PipelineParallel.SERIAL_CHUNKS)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = if (kotlin.math.abs(x - 10) <= r && kotlin.math.abs(y - 10) <= r) 100.0 else 0.0
                assertEquals("pixel ($x,$y)", expected, out[y * w + x], 0.0)
            }
        }
    }

    @Test
    fun parallelMatchesSerial() {
        val w = 64
        val h = 48
        val src = field(w, h, 0x5EEDL)
        for (r in intArrayOf(1, 4, 12)) {
            val serial = WindowedMax.dilate(src, w, h, r, PipelineParallel.SERIAL_CHUNKS)
            val parallel = WindowedMax.dilate(src, w, h, r, PipelineParallel.parallelism)
            assertArrayEquals("radius $r must be bit-identical parallel vs serial", serial, parallel, 0.0)
        }
    }

    @Test
    fun clampsEdgesToNearestSample() {
        // Left edge is the row max within radius; a bright pixel at x=0 spreads right by radius.
        val w = 8
        val src = DoubleArray(w) { if (it == 0) 50.0 else 0.0 }
        val out = WindowedMax.dilate(src, w, 1, 2, PipelineParallel.SERIAL_CHUNKS)
        assertEquals(50.0, out[0], 0.0)
        assertEquals(50.0, out[1], 0.0)
        assertEquals(50.0, out[2], 0.0)
        assertEquals(0.0, out[3], 0.0)
    }
}
