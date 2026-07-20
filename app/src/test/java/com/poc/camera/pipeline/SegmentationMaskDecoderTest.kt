package com.poc.camera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SegmentationMaskDecoderTest {

    private fun bufferOf(values: FloatArray, order: ByteOrder = ByteOrder.nativeOrder()): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 4).order(order)
        for (v in values) buffer.putFloat(v)
        buffer.flip()
        return buffer
    }

    @Test
    fun decodesRowMajorFloatsInNativeOrder() {
        val values = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.9f)
        val buffer = bufferOf(values)
        val decoded = SegmentationMaskDecoder.toFloatArray(buffer, width = 3, height = 2)
        assertArrayEquals(values, decoded, 1e-6f)
    }

    @Test
    fun leavesTheSourceBufferPositionUnchanged() {
        val values = floatArrayOf(1f, 0f, 1f, 0f)
        val buffer = bufferOf(values)
        val positionBefore = buffer.position()
        SegmentationMaskDecoder.toFloatArray(buffer, width = 2, height = 2)
        org.junit.Assert.assertEquals(positionBefore, buffer.position())
    }

    @Test
    fun decodesLittleEndianOrder() {
        val values = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val buffer = bufferOf(values, ByteOrder.LITTLE_ENDIAN)
        val decoded = SegmentationMaskDecoder.toFloatArray(buffer, width = 2, height = 2)
        assertArrayEquals(values, decoded, 1e-6f)
    }

    @Test
    fun rejectsBufferShorterThanWidthTimesHeight() {
        val buffer = bufferOf(floatArrayOf(0f, 1f, 2f))
        assertThrows(IllegalArgumentException::class.java) {
            SegmentationMaskDecoder.toFloatArray(buffer, width = 2, height = 2)
        }
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        val buffer = bufferOf(floatArrayOf(0f))
        assertThrows(IllegalArgumentException::class.java) {
            SegmentationMaskDecoder.toFloatArray(buffer, width = 0, height = 1)
        }
    }
}
