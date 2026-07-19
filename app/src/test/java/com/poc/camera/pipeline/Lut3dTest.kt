package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Lut3dTest {

    private val eps = 1e-4f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue("$message: expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    @Test
    fun identitySamplingReturnsInputAtLatticePoints() {
        val size = 17
        val lut = Lut3d.identity(size)
        val n1 = (size - 1).toFloat()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val r = ri / n1
                    val g = gi / n1
                    val b = bi / n1
                    val out = lut.sample(r, g, b)
                    assertClose(r, out[0], "R at ($ri,$gi,$bi)")
                    assertClose(g, out[1], "G at ($ri,$gi,$bi)")
                    assertClose(b, out[2], "B at ($ri,$gi,$bi)")
                }
            }
        }
    }

    @Test
    fun identitySamplingReturnsInputBetweenLatticePoints() {
        val lut = Lut3d.identity(17)
        val samples = listOf(
            Triple(0.03f, 0.51f, 0.97f),
            Triple(0.123f, 0.456f, 0.789f),
            Triple(0.5f, 0.5f, 0.5f),
            Triple(0.28f, 0.62f, 0.11f),
        )
        for ((r, g, b) in samples) {
            val out = lut.sample(r, g, b)
            assertClose(r, out[0], "R between lattice at ($r,$g,$b)")
            assertClose(g, out[1], "G between lattice at ($r,$g,$b)")
            assertClose(b, out[2], "B between lattice at ($r,$g,$b)")
        }
    }

    @Test
    fun interpolationBetweenTwoLatticeValuesIsLinear() {
        // A 2x2x2 LUT whose red channel encodes only the red axis (0 at ri=0,
        // 1 at ri=1) so sampling along red must be exactly linear.
        val size = 2
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    data[i++] = ri.toFloat() // R encodes the red axis
                    data[i++] = 0f
                    data[i++] = 0f
                }
            }
        }
        val lut = Lut3d(size, data)
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val out = lut.sample(t, 0f, 0f)
            assertClose(t, out[0], "linear red at t=$t")
        }
    }

    @Test
    fun outOfRangeInputsClamp() {
        val lut = Lut3d.identity(9)
        val low = lut.sample(-1f, -0.5f, -10f)
        assertClose(0f, low[0], "R clamps low")
        assertClose(0f, low[1], "G clamps low")
        assertClose(0f, low[2], "B clamps low")
        val high = lut.sample(2f, 5f, 1.5f)
        assertClose(1f, high[0], "R clamps high")
        assertClose(1f, high[1], "G clamps high")
        assertClose(1f, high[2], "B clamps high")
    }

    @Test
    fun atlasLayoutMatchesLatticeOrdering() {
        val size = 4
        val lut = Lut3d.identity(size)
        val atlas = lut.toAtlasRgb()
        val width = size * size
        val n1 = (size - 1).toFloat()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val x = bi * size + ri
                    val idx = (gi * width + x) * 3
                    assertEquals(ri / n1, atlas[idx], eps)
                    assertEquals(gi / n1, atlas[idx + 1], eps)
                    assertEquals(bi / n1, atlas[idx + 2], eps)
                }
            }
        }
        assertEquals(width * size * 3, atlas.size)
    }
}
