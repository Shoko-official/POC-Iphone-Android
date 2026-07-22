package com.poc.camera.pipeline

/**
 * Per-pixel FOLIAGE-likelihood prior in [0, 1], the foliage half of the semantic-region
 * rendering stage (issue #98). Like [SkyMask] it DRIVES a region-targeted rendition boost
 * rather than bounding operators: [SemanticRendering] uses it to enrich green and lift the
 * shadows of foliage (see that class). It is a soft, conservative membership tuned to fire
 * on green vegetation and to stay near zero on everything else.
 *
 * ## Two multiplicative priors, in the Rec. 601 opponent chroma space
 *
 * Same opponent space as [SkyMask] / [ChromaDenoiser] (documented axes, not calibrated
 * YCbCr), with the green-difference axis added:
 *
 *   Y  = 0.299R + 0.587G + 0.114B      // luma
 *   Cg = G - Y                         // green-difference chroma (positive for greens)
 *   Cr = R - Y                         // red-difference chroma
 *   Cb = B - Y                         // blue-difference chroma
 *
 *  1. CHROMA prior -- a smooth GREEN-SECTOR membership: a rising smoothstep on Cg (rewards
 *     green) gated by a falling smoothstep on Cr (rejects the reddish/yellow direction) AND
 *     a falling smoothstep on Cb (rejects the blue/cyan direction, which also carries a
 *     positive Cg). All three must agree, so pure greens score high while cyan (positive Cb),
 *     yellow-orange (positive Cr) and blue (negative Cg) score low. A neutral pixel scores 0
 *     on the Cg term, so a grayscale frame yields an all-zero mask -- which keeps the whole
 *     semantic stage a strict no-op on every grayscale scene.
 *  2. LUMA prior -- a mid-luma BAND (rising out of near-black, falling into near-white):
 *     foliage is a mid-tone, so crushed shadows and blown highlights are excluded, while the
 *     broad midtone range where vegetation lives passes.
 *
 * There is NO position prior: foliage can appear anywhere in the frame (a tree fills the top,
 * grass the bottom), unlike sky.
 *
 * ### Olive / yellow-green boundary (documented limitation)
 *
 * The green/yellow boundary is intrinsically soft: olive and yellow-green foliage sit right
 * where the Cr-reject smoothstep turns on, so they receive a PARTIAL membership that fades as
 * the hue warms toward yellow. This is by design -- a hard cutoff would flicker on real olive
 * vegetation -- and it means the boost tapers smoothly across that boundary rather than
 * snapping off. Deeply yellow (positive Cr) content is rejected; olive gets a reduced boost.
 *
 * The raw per-pixel prior is smoothed with a [BoxBlur] of [DEFAULT_BLUR_RADIUS] and clamped.
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object FoliageMask {

    // --- chroma (green sector) prior -----------------------------------------------

    /** Cg smoothstep knees (opponent G - Y): no green at/below [CG_LO], full green at/above [CG_HI]. */
    const val CG_LO = 8.0
    const val CG_HI = 24.0

    /** Cr smoothstep knees (opponent R - Y): full green at/below [CR_LO], rejected (yellow/red)
     *  at/above [CR_HI]. The olive/yellow-green softness lives in this transition. */
    const val CR_LO = 6.0
    const val CR_HI = 26.0

    /** Cb smoothstep knees (opponent B - Y): full green at/below [CB_LO], rejected (cyan/blue)
     *  at/above [CB_HI]. Rejects the bluish-green (cyan) that also carries a positive Cg. */
    const val CB_LO = 2.0
    const val CB_HI = 22.0

    // --- luma band prior -----------------------------------------------------------

    /** Lower luma band edge: below [LUMA_LO_KNEE0] crushed, full weight by [LUMA_LO_KNEE1]. */
    const val LUMA_LO_KNEE0 = 18.0
    const val LUMA_LO_KNEE1 = 46.0

    /** Upper luma band edge: full weight up to [LUMA_HI_KNEE0], excluded (blown) by [LUMA_HI_KNEE1]. */
    const val LUMA_HI_KNEE0 = 205.0
    const val LUMA_HI_KNEE1 = 236.0

    /** Spatial smoothing radius of the mask (coherent regions, no per-pixel flicker). */
    const val DEFAULT_BLUR_RADIUS = 12

    // Rec. 601 luma weights, matching [Luminance] / [ChromaDenoiser] / [ChromaRollOff].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Foliage-likelihood prior of [frame] in [0, 1], one value per pixel (row-major),
     * spatially smoothed with a box blur of [blurRadius]. A grayscale frame yields an
     * all-zero result.
     */
    fun compute(frame: Frame, blurRadius: Int = DEFAULT_BLUR_RADIUS): DoubleArray {
        val src = frame.argb
        val raw = DoubleArray(src.size)
        // Per-pixel prior is element-wise (row-parallel); the box blur is parallel internally
        // and bit-identical to the serial path.
        PipelineParallel.parallelRows(src.size) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val r = ((pixel shr 16) and 0xFF).toDouble()
                val g = ((pixel shr 8) and 0xFF).toDouble()
                val b = (pixel and 0xFF).toDouble()
                raw[i] = chromaLikelihood(r, g, b) * lumaBand(r, g, b)
            }
        }
        val smoothed = BoxBlur.blur(raw, frame.width, frame.height, blurRadius)
        for (i in smoothed.indices) smoothed[i] = smoothed[i].coerceIn(0.0, 1.0)
        return smoothed
    }

    /**
     * The pure green-sector chroma membership in [0, 1] (no luma gate): a rising smoothstep
     * on Cg, gated by falling smoothsteps on Cr (reject warm) and Cb (reject cyan/blue).
     * Exposed for direct testing of the rule.
     */
    fun chromaLikelihood(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        val cg = g - y
        val cr = r - y
        val cb = b - y
        val green = smoothstep(CG_LO, CG_HI, cg)
        val notWarm = 1.0 - smoothstep(CR_LO, CR_HI, cr)
        val notBlue = 1.0 - smoothstep(CB_LO, CB_HI, cb)
        return green * notWarm * notBlue
    }

    /** The mid-luma band prior in [0, 1]: rising out of near-black, falling into near-white. */
    fun lumaBand(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        val rise = smoothstep(LUMA_LO_KNEE0, LUMA_LO_KNEE1, y)
        val fall = 1.0 - smoothstep(LUMA_HI_KNEE0, LUMA_HI_KNEE1, y)
        return rise * fall
    }

    /** Classic smoothstep: 0 for x <= e0, 1 for x >= e1, C1-smooth in between. */
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
