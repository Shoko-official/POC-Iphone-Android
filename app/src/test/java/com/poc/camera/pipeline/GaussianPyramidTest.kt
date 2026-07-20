package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaussianPyramidTest {

    private fun randomPlane(width: Int, height: Int, seed: Long): PyramidPlane {
        var state = seed and 0xFFFFFFFFL
        val data = DoubleArray(width * height) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            ((state ushr 8) and 0xFF).toDouble()
        }
        return PyramidPlane(width, height, data)
    }

    /** (width, height) fixtures spanning odd, tiny, single-row and single-column shapes. */
    private val shapes = listOf(
        8 to 8,
        9 to 7, // odd
        17 to 13, // odd, several levels
        64 to 48,
        129 to 97, // odd, larger than a chunk
    )

    @Test
    fun downsampleHalvesDimensionsWithCeil() {
        val reduced = GaussianPyramid.downsample(randomPlane(9, 7, seed = 1L))
        assertEquals(5, reduced.width) // (9+1)/2
        assertEquals(4, reduced.height) // (7+1)/2
    }

    @Test
    fun constantPlaneSurvivesDownsampleAndUpsample() {
        // A flat field is pure DC: REDUCE and EXPAND must both preserve it exactly
        // (the x4 EXPAND gain is calibrated for exactly this), so the whole pyramid
        // stays at the constant value -- the kernel-normalisation invariant.
        val value = 137.0
        val base = PyramidPlane(65, 33, DoubleArray(65 * 33) { value })
        val levels = GaussianPyramid.build(base)
        for (level in levels) {
            for (v in level.data) assertTrue("flat level drifted to $v", abs(v - value) < 1e-9)
        }
        val up = GaussianPyramid.upsample(levels[1], base.width, base.height)
        for (v in up.data) assertTrue("flat upsample drifted to $v", abs(v - value) < 1e-9)
    }

    @Test
    fun buildStopsBeforeDroppingBelowMinDimension() {
        val base = randomPlane(128, 128, seed = 7L)
        val levels = GaussianPyramid.build(base, minDimension = 8)
        // 128 -> 64 -> 32 -> 16 -> 8; the next (4) would breach the floor.
        assertEquals(listOf(128, 64, 32, 16, 8), levels.map { it.width })
        assertTrue(levels.all { minOf(it.width, it.height) >= 8 })
    }

    @Test
    fun maxLevelsCapsDepth() {
        val base = randomPlane(128, 128, seed = 8L)
        val levels = GaussianPyramid.build(base, minDimension = 2, maxLevels = 3)
        assertEquals(3, levels.size)
    }

    @Test
    fun downsampleAndUpsampleAreSerialParallelBitIdentical() {
        for ((width, height) in shapes) {
            val base = randomPlane(width, height, seed = 0x5EEDL + width * 31L + height)
            val downSerial = GaussianPyramid.downsample(base, PipelineParallel.SERIAL_CHUNKS)
            val downParallel = GaussianPyramid.downsample(base, PipelineParallel.parallelism)
            assertArrayEquals("down ${width}x$height", downSerial.data, downParallel.data, 0.0)

            val upSerial = GaussianPyramid.upsample(downSerial, width, height, PipelineParallel.SERIAL_CHUNKS)
            val upParallel = GaussianPyramid.upsample(downParallel, width, height, PipelineParallel.parallelism)
            assertArrayEquals("up ${width}x$height", upSerial.data, upParallel.data, 0.0)
        }
    }
}
