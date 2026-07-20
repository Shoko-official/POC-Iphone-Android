package com.poc.camera.pipeline

/**
 * Three-point parabola vertex used for sub-pixel refinement of a discrete cost
 * curve (here the per-tile MAD sampled at integer offsets).
 *
 * Given the cost at positions -1, 0 and +1, fits y = a*x^2 + b*x + c and returns
 * the vertex abscissa relative to 0. Only convex minima are meaningful, so a flat
 * or concave triple (non-positive curvature) is treated as degenerate and yields
 * 0.0. The result is clamped to [-0.5, 0.5]: a true minimum farther than half a
 * sample away means the discrete search picked the wrong integer offset, not a
 * sub-pixel correction.
 */
object Parabola {

    /** Vertex offset in [-0.5, 0.5]; 0.0 for flat or concave (degenerate) triples. */
    fun vertex(yMinus1: Double, yZero: Double, yPlus1: Double): Double {
        val curvature = yMinus1 + yPlus1 - 2.0 * yZero
        if (curvature <= CURVATURE_EPS) return 0.0
        val frac = 0.5 * (yMinus1 - yPlus1) / curvature
        return frac.coerceIn(-0.5, 0.5)
    }

    private const val CURVATURE_EPS = 1e-12
}
