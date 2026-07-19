package com.poc.camera.pipeline

import kotlin.math.floor
import kotlin.math.min

/**
 * A 3D colour lookup table sampled with trilinear interpolation. Pure data and
 * math with no Android or GL dependencies, so it is exercised directly in JVM
 * unit tests and shared verbatim with the GL video path.
 *
 * The lattice has [size] samples per axis (typically 17). [data] holds the
 * flattened RGB triples in [0, 1], one per lattice point, ordered red-fastest:
 *
 *   index(ri, gi, bi) = ((bi * size + gi) * size + ri) * 3
 *
 * so red varies fastest, then green, then blue. [toAtlasRgb] lays the same data
 * out as a 2D atlas (N horizontal slices of NxN) matching the shader in
 * [LutShaderSource]; keeping both here guarantees the CPU tests and the GPU
 * shader agree on ordering.
 */
class Lut3d(val size: Int, val data: FloatArray) {

    init {
        require(size >= 2) { "LUT size must be at least 2, was $size" }
        val expected = size * size * size * 3
        require(data.size == expected) {
            "LUT data must hold $expected floats for size $size, was ${data.size}"
        }
    }

    /** Flat index of the red channel of lattice point ([ri], [gi], [bi]). */
    private fun latticeIndex(ri: Int, gi: Int, bi: Int): Int =
        ((bi * size + gi) * size + ri) * 3

    /**
     * Trilinearly interpolates the look at ([r], [g], [b]). Inputs are clamped
     * to [0, 1]; the returned triple is in [0, 1] whenever [data] is. At a
     * lattice point the result is that point's value within rounding.
     */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n1 = size - 1
        val rf = r.coerceIn(0f, 1f) * n1
        val gf = g.coerceIn(0f, 1f) * n1
        val bf = b.coerceIn(0f, 1f) * n1

        val r0 = floor(rf).toInt()
        val g0 = floor(gf).toInt()
        val b0 = floor(bf).toInt()
        val r1 = min(r0 + 1, n1)
        val g1 = min(g0 + 1, n1)
        val b1 = min(b0 + 1, n1)
        val rd = rf - r0
        val gd = gf - g0
        val bd = bf - b0

        val out = FloatArray(3)
        for (c in 0..2) {
            // Interpolate the four red-green corners on each blue plane, then
            // blend the two planes: standard trilinear as three nested lerps.
            val c000 = data[latticeIndex(r0, g0, b0) + c]
            val c100 = data[latticeIndex(r1, g0, b0) + c]
            val c010 = data[latticeIndex(r0, g1, b0) + c]
            val c110 = data[latticeIndex(r1, g1, b0) + c]
            val c001 = data[latticeIndex(r0, g0, b1) + c]
            val c101 = data[latticeIndex(r1, g0, b1) + c]
            val c011 = data[latticeIndex(r0, g1, b1) + c]
            val c111 = data[latticeIndex(r1, g1, b1) + c]

            val c00 = lerp(c000, c100, rd)
            val c10 = lerp(c010, c110, rd)
            val c01 = lerp(c001, c101, rd)
            val c11 = lerp(c011, c111, rd)
            val c0 = lerp(c00, c10, gd)
            val c1 = lerp(c01, c11, gd)
            out[c] = lerp(c0, c1, bd)
        }
        return out
    }

    /**
     * Flattens the LUT into a row-major RGB atlas of N blue slices laid out
     * horizontally: width = size*size, height = size. Atlas pixel (x, y) holds
     * the lattice value at red = x mod size, green = y, blue = x / size, so the
     * shader can address it as documented in [LutShaderSource].
     */
    fun toAtlasRgb(): FloatArray {
        val width = size * size
        val out = FloatArray(width * size * 3)
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val x = bi * size + ri
                    val atlas = (gi * width + x) * 3
                    val lattice = latticeIndex(ri, gi, bi)
                    out[atlas] = data[lattice]
                    out[atlas + 1] = data[lattice + 1]
                    out[atlas + 2] = data[lattice + 2]
                }
            }
        }
        return out
    }

    companion object {
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** Identity LUT: each lattice point maps to its own normalised coordinate. */
        fun identity(size: Int): Lut3d {
            require(size >= 2) { "LUT size must be at least 2, was $size" }
            val n1 = (size - 1).toFloat()
            val data = FloatArray(size * size * size * 3)
            var i = 0
            for (bi in 0 until size) {
                for (gi in 0 until size) {
                    for (ri in 0 until size) {
                        data[i++] = ri / n1
                        data[i++] = gi / n1
                        data[i++] = bi / n1
                    }
                }
            }
            return Lut3d(size, data)
        }
    }
}
