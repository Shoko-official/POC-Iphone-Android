package com.poc.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Proves the row/column-parallel hot stages produce output BIT-IDENTICAL to their
 * serial reference path. The serial reference is the same code forced onto a single
 * chunk ([PipelineParallel.SERIAL_CHUNKS]), which is exactly the pre-parallelisation
 * loop; the parallel path splits into one chunk per worker. Because each output element
 * is computed in the same arithmetic order in both paths (only independent elements are
 * spread across threads), the doubles must match exactly, so every comparison uses a
 * zero tolerance.
 *
 * Fixtures deliberately include odd sizes, sizes smaller than the worker count, and
 * degenerate 1-row / 1-column images -- the cases where naive chunking would drop or
 * double-count work.
 */
class ParallelDeterminismTest {

    private fun randomField(size: Int, seed: Long): DoubleArray {
        var state = seed and 0xFFFFFFFFL
        return DoubleArray(size) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFFFF).toDouble()
        }
    }

    /** (width, height) fixtures spanning odd, tiny, single-row and single-column shapes. */
    private val shapes = listOf(
        1 to 1,
        1 to 200, // single column
        200 to 1, // single row
        3 to 3, // smaller than the worker count
        17 to 5, // odd
        13 to 13, // odd square
        64 to 48,
        129 to 97, // odd, larger than a chunk
    )

    @Test
    fun boxBlurParallelMatchesSerial() {
        for ((width, height) in shapes) {
            val src = randomField(width * height, seed = 0x5EED + width * 31L + height)
            for (radius in intArrayOf(0, 1, 2, 5)) {
                val serial = BoxBlur.blur(src, width, height, radius, PipelineParallel.SERIAL_CHUNKS)
                val parallel = BoxBlur.blur(src, width, height, radius, PipelineParallel.parallelism)
                assertArrayEquals(
                    "box blur ${width}x$height r=$radius",
                    serial, parallel, 0.0,
                )
            }
        }
    }

    @Test
    fun selfGuidedParallelMatchesSerial() {
        for ((width, height) in shapes) {
            val image = randomField(width * height, seed = 0xABCDE + width * 7L + height)
            for (radius in intArrayOf(0, 1, 4)) {
                val eps = 8.0
                val serial = GuidedFilter.selfGuided(image, width, height, radius, eps, PipelineParallel.SERIAL_CHUNKS)
                val parallel = GuidedFilter.selfGuided(image, width, height, radius, eps, PipelineParallel.parallelism)
                assertArrayEquals(
                    "selfGuided ${width}x$height r=$radius",
                    serial, parallel, 0.0,
                )
            }
        }
    }

    @Test
    fun guidedParallelMatchesSerial() {
        for ((width, height) in shapes) {
            val input = randomField(width * height, seed = 0x13579 + width * 11L + height)
            val guide = randomField(width * height, seed = 0x2468A + width * 13L + height)
            for (radius in intArrayOf(0, 1, 4)) {
                val eps = 12.0
                val serial = GuidedFilter.guided(input, guide, width, height, radius, eps, PipelineParallel.SERIAL_CHUNKS)
                val parallel = GuidedFilter.guided(input, guide, width, height, radius, eps, PipelineParallel.parallelism)
                assertArrayEquals(
                    "guided ${width}x$height r=$radius",
                    serial, parallel, 0.0,
                )
            }
        }
    }
}
