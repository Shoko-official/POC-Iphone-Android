package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRingBufferTest {

    private fun frame(timestampMillis: Long) = Frame(
        width = 1,
        height = 1,
        argb = intArrayOf(timestampMillis.toInt()),
        timestampMillis = timestampMillis,
    )

    @Test
    fun rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException::class.java) { FrameRingBuffer(capacity = 0) }
    }

    @Test
    fun snapshotIsEmptyInitially() {
        val buffer = FrameRingBuffer(capacity = 3)

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test
    fun snapshotIsOrderedOldestToNewestWithinCapacity() {
        val buffer = FrameRingBuffer(capacity = 3)

        buffer.add(frame(1L))
        buffer.add(frame(2L))

        assertEquals(listOf(frame(1L), frame(2L)), buffer.snapshot())
        assertEquals(2, buffer.size)
    }

    @Test
    fun evictsOldestFrameWhenCapacityExceeded() {
        val buffer = FrameRingBuffer(capacity = 2)

        buffer.add(frame(1L))
        buffer.add(frame(2L))
        buffer.add(frame(3L))

        assertEquals(listOf(frame(2L), frame(3L)), buffer.snapshot())
        assertEquals(2, buffer.size)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val buffer = FrameRingBuffer(capacity = 2)
        buffer.add(frame(1L))

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test
    fun snapshotIsIsolatedFromSubsequentAdds() {
        val buffer = FrameRingBuffer(capacity = 2)
        buffer.add(frame(1L))

        val snapshot = buffer.snapshot()
        buffer.add(frame(2L))
        buffer.add(frame(3L))

        assertEquals(listOf(frame(1L)), snapshot)
        assertEquals(listOf(frame(2L), frame(3L)), buffer.snapshot())
    }
}
