package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class KernelSplatTest {

    private class Grid(val width: Int, val height: Int) {
        val r = FloatArray(width * height)
        val g = FloatArray(width * height)
        val b = FloatArray(width * height)
        val w = FloatArray(width * height)

        fun splat(x: Double, y: Double, rr: Float, gg: Float, bb: Float, weight: Float) =
            KernelSplat.splat(r, g, b, w, width, height, x, y, rr, gg, bb, weight)
    }

    @Test
    fun exactHalfTexelSpreadsEquallyOverFourTexels() {
        val grid = Grid(4, 4)
        grid.splat(1.5, 1.5, 255f, 128f, 64f, 1f)

        // Each of the four straddling texels gets a quarter of the weight and colour.
        for (pair in listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2)) {
            val i = pair.second * 4 + pair.first
            assertEquals(0.25f, grid.w[i], 1e-6f)
            assertEquals(0.25f * 255f, grid.r[i], 1e-4f)
            assertEquals(0.25f * 128f, grid.g[i], 1e-4f)
            assertEquals(0.25f * 64f, grid.b[i], 1e-4f)
        }
        // Everything else is untouched.
        assertEquals(0f, grid.w[0], 0f)
        assertEquals(0f, grid.w[0 * 4 + 3], 0f)
    }

    @Test
    fun exactIntegerLandsWhollyOnOneTexel() {
        val grid = Grid(4, 4)
        grid.splat(2.0, 2.0, 200f, 100f, 50f, 1f)

        val i = 2 * 4 + 2
        assertEquals(1f, grid.w[i], 1e-6f)
        assertEquals(200f, grid.r[i], 1e-4f)
        // No spill into neighbours.
        assertEquals(0f, grid.w[2 * 4 + 3], 0f)
        assertEquals(0f, grid.w[3 * 4 + 2], 0f)
    }

    @Test
    fun offGridTexelsAreDroppedLeavingPartialCoverage() {
        val grid = Grid(4, 4)
        // (3.5, 3.5): only the (3, 3) corner is inside a 4x4 grid; the other three are off-grid.
        grid.splat(3.5, 3.5, 255f, 255f, 255f, 1f)

        val i = 3 * 4 + 3
        assertEquals(0.25f, grid.w[i], 1e-6f)
        // Total accumulated weight is just the in-bounds quarter.
        assertEquals(0.25f, grid.w.sum(), 1e-6f)
    }

    @Test
    fun coverageNormalisationYieldsWeightedMean() {
        val grid = Grid(4, 4)
        // Two samples land squarely on the same texel with different weights and colours.
        grid.splat(1.0, 1.0, 60f, 0f, 0f, 1f)
        grid.splat(1.0, 1.0, 200f, 0f, 0f, 3f)

        val i = 1 * 4 + 1
        assertEquals(4f, grid.w[i], 1e-6f)
        // Weighted mean = (1*60 + 3*200) / 4 = 165.
        assertEquals(165f, grid.r[i] / grid.w[i], 1e-4f)
    }

    @Test
    fun zeroWeightSplatIsANoOp() {
        val grid = Grid(4, 4)
        grid.splat(1.5, 1.5, 255f, 255f, 255f, 0f)
        assertEquals(0f, grid.w.sum(), 0f)
    }
}
