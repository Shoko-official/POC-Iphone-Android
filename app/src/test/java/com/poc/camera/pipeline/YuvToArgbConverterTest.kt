package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvToArgbConverterTest {

    /** Builds a [ByteArray] from unsigned 0-255 sample values (YUV samples are unsigned). */
    private fun unsignedBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun IntArray.channels(index: Int): Triple<Int, Int, Int> {
        val pixel = this[index]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return Triple(r, g, b)
    }

    private fun assertChannelsWithinTolerance(
        expected: Triple<Int, Int, Int>,
        actual: Triple<Int, Int, Int>,
        tolerance: Int = 1,
    ) {
        assertTrue(
            "expected $expected within $tolerance of $actual",
            kotlin.math.abs(expected.first - actual.first) <= tolerance,
        )
        assertTrue(
            "expected $expected within $tolerance of $actual",
            kotlin.math.abs(expected.second - actual.second) <= tolerance,
        )
        assertTrue(
            "expected $expected within $tolerance of $actual",
            kotlin.math.abs(expected.third - actual.third) <= tolerance,
        )
    }

    @Test
    fun convertsFlatMidGray2x2ImageWithFullAlpha() {
        // Y=128, U=128, V=128 for every pixel of a 2x2 image (pixelStride 1, no
        // subsampling gaps) should decode to a neutral gray with an opaque alpha byte.
        val yBytes = unsignedBytes(128, 128, 128, 128)
        val uBytes = unsignedBytes(128)
        val vBytes = unsignedBytes(128)

        val argb = YuvToArgbConverter.convert(
            width = 2,
            height = 2,
            yBytes = yBytes,
            yRowStride = 2,
            yPixelStride = 1,
            uBytes = uBytes,
            uRowStride = 1,
            uPixelStride = 1,
            vBytes = vBytes,
            vRowStride = 1,
            vPixelStride = 1,
        )

        assertEquals(4, argb.size)
        for (pixel in argb) {
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
            assertChannelsWithinTolerance(Triple(128, 128, 128), Triple((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF))
        }
    }

    @Test
    fun convertsKnownPureColorBlocks() {
        // BT.601 full-range Y/Cb/Cr values for pure red/green/blue, each filling its
        // own 2x2 chroma-subsampled block laid out side by side in a 4x2 image.
        val yBytes = unsignedBytes(
            76, 76, 150, 150,
            76, 76, 150, 150,
        )
        val uBytes = unsignedBytes(85, 44)
        val vBytes = unsignedBytes(255, 21)

        val argb = YuvToArgbConverter.convert(
            width = 4,
            height = 2,
            yBytes = yBytes,
            yRowStride = 4,
            yPixelStride = 1,
            uBytes = uBytes,
            uRowStride = 2,
            uPixelStride = 1,
            vBytes = vBytes,
            vRowStride = 2,
            vPixelStride = 1,
        )

        assertChannelsWithinTolerance(Triple(255, 0, 0), argb.channels(0))
        assertChannelsWithinTolerance(Triple(0, 255, 0), argb.channels(2))
    }

    @Test
    fun honorsPixelStrideTwoForInterleavedChromaPlanes() {
        // Simulates a semi-planar layout where U/V bytes are interleaved with a
        // companion channel: real samples sit at even offsets (pixelStride 2), and the
        // odd offsets hold unrelated bytes that must be skipped, not read.
        val yBytes = unsignedBytes(*IntArray(16) { 128 })
        val uBytes = unsignedBytes(
            200, 0, 200, 0,
            200, 0, 200, 0,
        )
        val vBytes = unsignedBytes(
            90, 0, 90, 0,
            90, 0, 90, 0,
        )

        val argb = YuvToArgbConverter.convert(
            width = 4,
            height = 4,
            yBytes = yBytes,
            yRowStride = 4,
            yPixelStride = 1,
            uBytes = uBytes,
            uRowStride = 4,
            uPixelStride = 2,
            vBytes = vBytes,
            vRowStride = 4,
            vPixelStride = 2,
        )

        val expected = Triple(75, 130, 255)
        for (index in argb.indices) {
            assertChannelsWithinTolerance(expected, argb.channels(index))
        }
    }
}
