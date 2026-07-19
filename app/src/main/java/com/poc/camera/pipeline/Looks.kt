package com.poc.camera.pipeline

/**
 * Factory for the shipped video looks, each built as a [Lut3d] by evaluating a
 * pure per-lattice-point formula. Deterministic and free of Android/GL
 * dependencies so the exact grade is unit-tested on the JVM and then uploaded
 * verbatim to the GPU for preview and recording.
 */
object Looks {

    // Cinematic knobs (all gentle so the grade stays natural and the gray axis
    // stays monotonic; see the class doc for how each stays luma-increasing).
    private const val S_CURVE_AMOUNT = 0.35f // blend toward the luma S-curve
    private const val WARM_HIGHLIGHTS = 0.06f // orange push weighted by brightness
    private const val COOL_SHADOWS = 0.06f // teal push weighted by darkness
    private const val SATURATION = 0.90f // gentle desaturation cap (luma-preserving)

    // Rec. 601 luma weights, matching Luminance/Saturation elsewhere in the pipeline.
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /** Neutral look: the identity LUT, i.e. no colour change. */
    fun neutral(size: Int): Lut3d = Lut3d.identity(size)

    /**
     * Cinematic teal-orange look built over the LUT lattice.
     *
     * For each normalised lattice colour the formula applies, in order:
     *  1. a mild S-curve on luma (smoothstep blended by [S_CURVE_AMOUNT]),
     *     applied as an equal per-channel brightness shift so hue is preserved;
     *  2. a split-tone that pushes highlights toward orange and shadows toward
     *     teal, weighted by the S-curved luma;
     *  3. a gentle luma-preserving desaturation (factor [SATURATION]).
     *
     * Every stage is monotonically increasing in the input luma, so along the
     * gray ramp the output luma stays monotonic (verified in tests), and all
     * channels are clamped to [0, 1].
     */
    fun cinematic(size: Int): Lut3d {
        val n1 = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val r = ri / n1
                    val g = gi / n1
                    val b = bi / n1
                    val graded = grade(r, g, b)
                    data[i++] = graded[0]
                    data[i++] = graded[1]
                    data[i++] = graded[2]
                }
            }
        }
        return Lut3d(size, data)
    }

    private fun grade(r: Float, g: Float, b: Float): FloatArray {
        val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b

        // 1. Mild S-curve on luma, applied as a uniform brightness shift.
        val sCurved = luma + S_CURVE_AMOUNT * (smoothstep(luma) - luma)
        val dL = sCurved - luma
        var nr = r + dL
        var ng = g + dL
        var nb = b + dL

        // 2. Teal-orange split tone, weighted by the S-curved luma.
        val highlight = sCurved.coerceIn(0f, 1f)
        val shadow = 1f - highlight
        nr += WARM_HIGHLIGHTS * highlight - COOL_SHADOWS * shadow
        ng += 0.5f * WARM_HIGHLIGHTS * highlight
        nb += -WARM_HIGHLIGHTS * highlight + COOL_SHADOWS * shadow

        // 3. Gentle luma-preserving desaturation around the new colour's luma.
        val chromaLuma = LUMA_R * nr + LUMA_G * ng + LUMA_B * nb
        nr = chromaLuma + SATURATION * (nr - chromaLuma)
        ng = chromaLuma + SATURATION * (ng - chromaLuma)
        nb = chromaLuma + SATURATION * (nb - chromaLuma)

        return floatArrayOf(
            nr.coerceIn(0f, 1f),
            ng.coerceIn(0f, 1f),
            nb.coerceIn(0f, 1f),
        )
    }

    /** Smoothstep S-curve on [0, 1]: 3t^2 - 2t^3, monotonically increasing. */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
