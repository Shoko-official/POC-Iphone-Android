package com.poc.camera.pipeline

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [PipelineParallel] partitioning and exception contract. The
 * bit-identity of the parallel pipeline stages is proven separately in
 * [ParallelDeterminismTest] and by the golden regression suites.
 */
class PipelineParallelTest {

    /** Every row index in [0, height) is visited exactly once across all chunks. */
    @Test
    fun partitionCoversEveryRowExactlyOnce() {
        for (height in intArrayOf(1, 2, 3, 7, 16, 1000)) {
            for (chunkCount in intArrayOf(1, 2, 3, 4, 8, 64)) {
                val visits = IntArray(height)
                PipelineParallel.parallelRows(height, chunkCount) { start, end ->
                    for (i in start until end) visits[i]++
                }
                for (i in 0 until height) {
                    assertEquals("height=$height chunks=$chunkCount index=$i", 1, visits[i])
                }
            }
        }
    }

    /** Chunks are contiguous and non-overlapping (end of one == start of the next). */
    @Test
    fun chunksAreContiguousHalfOpenRanges() {
        val ranges = ArrayList<Pair<Int, Int>>()
        PipelineParallel.parallelRows(1000, chunkCount = 7) { start, end ->
            synchronized(ranges) { ranges.add(start to end) }
        }
        ranges.sortBy { it.first }
        assertEquals(0, ranges.first().first)
        assertEquals(1000, ranges.last().second)
        for (i in 1 until ranges.size) {
            assertEquals("gap/overlap at chunk $i", ranges[i - 1].second, ranges[i].first)
        }
        // Chunk sizes differ by at most one row.
        val sizes = ranges.map { it.second - it.first }
        assertTrue(sizes.max() - sizes.min() <= 1)
    }

    @Test
    fun zeroHeightRunsNothing() {
        val runs = AtomicInteger(0)
        PipelineParallel.parallelRows(0) { _, _ -> runs.incrementAndGet() }
        assertEquals(0, runs.get())
    }

    @Test
    fun chunkCountLargerThanHeightGivesSingleRowChunks() {
        val visits = IntArray(3)
        PipelineParallel.parallelRows(3, chunkCount = 64) { start, end ->
            for (i in start until end) visits[i]++
        }
        assertArrayEquals(intArrayOf(1, 1, 1), visits)
    }

    @Test
    fun serialChunkCountRunsWholeRangeInOneCall() {
        val calls = AtomicInteger(0)
        PipelineParallel.parallelRows(500, PipelineParallel.SERIAL_CHUNKS) { start, end ->
            calls.incrementAndGet()
            assertEquals(0, start)
            assertEquals(500, end)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun firstChunkFailurePropagates() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            PipelineParallel.parallelRows(1000, chunkCount = 8) { start, _ ->
                if (start == 0) throw IllegalStateException("boom")
            }
        }
        assertEquals("boom", thrown.message)
    }

    @Test
    fun laterChunkFailurePropagates() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            PipelineParallel.parallelRows(1000, chunkCount = 8) { start, _ ->
                // Fail only in a non-first chunk (start > 0).
                if (start > 0) throw IllegalStateException("later")
            }
        }
        assertEquals("later", thrown.message)
    }

    @Test
    fun invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PipelineParallel.parallelRows(-1) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PipelineParallel.parallelRows(10, chunkCount = 0) { _, _ -> }
        }
    }
}
