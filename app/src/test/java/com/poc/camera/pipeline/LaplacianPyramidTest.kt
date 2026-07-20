package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaplacianPyramidTest {

    private fun randomPlane(width: Int, height: Int, seed: Long): PyramidPlane {
        var state = seed and 0xFFFFFFFFL
        val data = DoubleArray(width * height) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFF).toDouble()
        }
        return PyramidPlane(width, height, data)
    }

    private val shapes = listOf(
        8 to 8,
        9 to 7, // odd
        17 to 13, // odd, several levels
        64 to 48,
        129 to 97, // odd, larger than a chunk
    )

    @Test
    fun bandCountMatchesGaussianLevels() {
        val gaussian = GaussianPyramid.build(randomPlane(64, 64, seed = 1L))
        val bands = LaplacianPyramid.build(gaussian)
        assertEquals(gaussian.size, bands.size)
        // The coarsest band is the residual Gaussian level verbatim.
        assertArrayEquals(gaussian.last().data, bands.last().data, 0.0)
    }

    @Test
    fun buildThenCollapseReconstructsWithinRoundingBound() {
        // Reconstruction is exact by construction modulo float rounding, because build
        // and collapse share the same EXPAND. In the double path the error sits at the
        // rounding floor -- far under the "<= 2 codes" bound the float path would need.
        for ((width, height) in shapes) {
            val base = randomPlane(width, height, seed = 0xABCDEL + width * 7L + height)
            val bands = LaplacianPyramid.build(GaussianPyramid.build(base))
            val collapsed = LaplacianPyramid.collapse(bands)
            assertEquals(base.width, collapsed.width)
            assertEquals(base.height, collapsed.height)
            var maxError = 0.0
            for (i in base.data.indices) maxError = maxOf(maxError, abs(base.data[i] - collapsed.data[i]))
            assertTrue("reconstruction error $maxError too large at ${width}x$height", maxError < 1e-6)
        }
    }

    @Test
    fun buildAndCollapseAreSerialParallelBitIdentical() {
        for ((width, height) in shapes) {
            val base = randomPlane(width, height, seed = 0x13579L + width * 11L + height)
            val gaussian = GaussianPyramid.build(base, chunkCount = PipelineParallel.SERIAL_CHUNKS)

            val bandsSerial = LaplacianPyramid.build(gaussian, PipelineParallel.SERIAL_CHUNKS)
            val bandsParallel = LaplacianPyramid.build(gaussian, PipelineParallel.parallelism)
            for (l in bandsSerial.indices) {
                assertArrayEquals("band $l ${width}x$height", bandsSerial[l].data, bandsParallel[l].data, 0.0)
            }

            val collapseSerial = LaplacianPyramid.collapse(bandsSerial, PipelineParallel.SERIAL_CHUNKS)
            val collapseParallel = LaplacianPyramid.collapse(bandsParallel, PipelineParallel.parallelism)
            assertArrayEquals("collapse ${width}x$height", collapseSerial.data, collapseParallel.data, 0.0)
        }
    }
}
