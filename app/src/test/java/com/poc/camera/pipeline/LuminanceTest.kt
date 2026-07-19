package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class LuminanceTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun extractsRec601LumaForPrimaries() {
        val frame = Frame(
            width = 4,
            height = 1,
            argb = intArrayOf(
                argb(255, 0, 0),
                argb(0, 255, 0),
                argb(0, 0, 255),
                argb(128, 128, 128),
            ),
            timestampMillis = 0L,
        )

        val luma = Luminance.extract(frame)

        // 0.299*255=76.245, 0.587*255=149.685, 0.114*255=29.07, gray stays 128.
        assertEquals(4, luma.width)
        assertEquals(1, luma.height)
        assertEquals(intArrayOf(76, 150, 29, 128).toList(), luma.values.toList())
    }

    @Test
    fun downsampleAveragesEach2x2Block() {
        // 4x4 laid out so each 2x2 block has a known integer average.
        val plane = LumaPlane(
            width = 4,
            height = 4,
            values = intArrayOf(
                10, 20, 100, 100,
                30, 40, 100, 100,
                0, 0, 5, 9,
                0, 4, 7, 9,
            ),
        )

        val down = Luminance.downsample2x(plane)

        assertEquals(2, down.width)
        assertEquals(2, down.height)
        // Block averages with round-half-up: (10+20+30+40)/4=25, (100*4)/4=100,
        // (0+0+0+4)/4=1, (5+9+7+9)/4=7.5 -> 8.
        assertEquals(intArrayOf(25, 100, 1, 8).toList(), down.values.toList())
    }

    @Test
    fun downsampleDropsTrailingOddRowAndColumn() {
        val plane = LumaPlane(
            width = 3,
            height = 3,
            values = intArrayOf(
                4, 8, 99,
                12, 16, 99,
                99, 99, 99,
            ),
        )

        val down = Luminance.downsample2x(plane)

        assertEquals(1, down.width)
        assertEquals(1, down.height)
        assertEquals(listOf(10), down.values.toList()) // (4+8+12+16)/4 = 10
    }
}
