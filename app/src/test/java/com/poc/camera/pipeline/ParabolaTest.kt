package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ParabolaTest {

    @Test
    fun recoversVertexOfAnExactQuadratic() {
        // Samples of y = (x - 0.3)^2 at x = -1, 0, +1 -> vertex at +0.30.
        assertEquals(0.30, Parabola.vertex(1.69, 0.09, 0.49), 1e-9)
    }

    @Test
    fun recoversNegativeVertex() {
        // Samples of y = (x + 0.25)^2 -> vertex at -0.25.
        assertEquals(-0.25, Parabola.vertex(0.5625, 0.0625, 1.5625), 1e-9)
    }

    @Test
    fun symmetricMinimumHasZeroFraction() {
        assertEquals(0.0, Parabola.vertex(1.0, 0.0, 1.0), 1e-12)
    }

    @Test
    fun clampsVertexBeyondHalfASample() {
        // Raw vertex would be +0.625; clamped to +0.5.
        assertEquals(0.5, Parabola.vertex(10.0, 1.0, 0.0), 1e-12)
        // Raw vertex would be -0.625; clamped to -0.5.
        assertEquals(-0.5, Parabola.vertex(0.0, 1.0, 10.0), 1e-12)
    }

    @Test
    fun flatTripleFallsBackToZero() {
        assertEquals(0.0, Parabola.vertex(5.0, 5.0, 5.0), 1e-12)
    }

    @Test
    fun concaveTripleFallsBackToZero() {
        // A maximum (negative curvature) is degenerate for a minimum search.
        assertEquals(0.0, Parabola.vertex(0.0, 5.0, 0.0), 1e-12)
    }
}
