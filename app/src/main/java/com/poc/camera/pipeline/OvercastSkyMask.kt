package com.poc.camera.pipeline

import kotlin.math.sqrt

/**
 * Per-pixel OVERCAST-SKY-likelihood prior in [0, 1] (issue #106), the luminance-texture
 * companion to [SkyMask]. [SkyMask]'s chroma prior requires blue-cyan chroma, so a gray or
 * overcast sky reads ~0 there (its documented limitation) and used to receive none of the sky
 * treatment. This prior fills that gap with a colour-free cue -- bright, LOW-TEXTURE,
 * near-NEUTRAL-chroma content in the upper image -- and [SemanticRendering] uses it to drive
 * the sky chroma-noise / gradient-banding smoothing ONLY, never the blue deepening: an
 * overcast sky must STAY GRAY, so the one adjustment it earns is the mean-preserving smooth.
 *
 * ## Four multiplicative priors
 *
 * All four are in [0, 1] and multiplied, so ALL must agree for a pixel to read as overcast
 * sky. Luma and the opponent chroma axes match [SkyMask] / [ChromaDenoiser] (Rec. 601).
 *
 *  1. TEXTURE prior -- overcast sky is SMOOTH. The cue is the local NON-PLANAR luma detail
 *     energy: `detail = Y - boxMean(Y, TEXTURE_RADIUS)`, energy `boxMean(detail^2,
 *     TEXTURE_RADIUS)`, mapped through a FALLING smoothstep from [VAR_LO] to [VAR_HI]
 *     (units: code^2). A plain box variance (`mean(Y^2) - mean(Y)^2`) was evaluated first
 *     and rejected: it counts a smooth gradient's SLOPE as texture, so it suppresses the
 *     very vertical gradient a real overcast sky carries while under-counting a smoothly
 *     interpolated wall texture (locally near-planar at any single radius). The residual
 *     energy is ~0 on any linear ramp interior (the box mean of a plane is the plane) yet
 *     high on curved textured surfaces, which is exactly the smooth-sky-vs-textured-wall
 *     separation needed. Measured on the fixtures (code^2, radius [TEXTURE_RADIUS]): the
 *     overcastscene sky band reads ~0.3 clean / ~8 after a merged burst (the residual luma
 *     noise floor), while its textured wall band reads ~25 at the 5th percentile and ~66 at
 *     the median, and the backlitportrait background band ~30 at the median. The knees
 *     [VAR_LO] = 12 / [VAR_HI] = 36 sit between those populations (residual std ~3.5 -> 6
 *     codes); they are TUNED ON THESE FIXTURES, deliberately tighter than a first-principles
 *     guess, because the wall/sky distributions genuinely overlap at looser knees.
 *  2. BRIGHT prior -- an overcast sky is bright even though it is gray, so a rising
 *     smoothstep rewards luma above [LUMA_LO], full at/above [LUMA_HI]. Unlike [SkyMask]
 *     there is NO dusk floor: without chroma or brightness there is no evidence left that a
 *     smooth region is sky rather than wall, so dark gray content never fires.
 *  3. NEUTRALITY prior -- the separator from BLUE sky (already handled by [SkyMask]) and
 *     from coloured walls/skin: chroma magnitude `sqrt(Cr^2 + Cb^2)` must be LOW, a falling
 *     smoothstep from [CHROMA_LO] to [CHROMA_HI]. Every skin tone sits FAR above the
 *     [CHROMA_HI] = 12 ceiling (the skin-chart patches measure chroma magnitude 32..67, the
 *     landscape/backlit skin patches 61/17+ -- and the darker ones also fail the bright
 *     prior), so bright smooth skin cannot fire and no skin-mask exclusion is needed.
 *  4. POSITION prior -- [SkyMask.positionWeight], the shared vertical ramp: sky lives in the
 *     upper image. Like [SkyMask.compute] it is a function of ABSOLUTE image row, so
 *     [compute] takes [rowOffset] / [imageHeight] and a [TiledFinishing] tile reproduces the
 *     whole-frame weight at each row.
 *
 * ### Honest false positives (documented by design)
 *
 * A bright, smooth, neutral region in the upper image is INDISTINGUISHABLE from an overcast
 * sky by these cues: the "gradients" and "highcontrast" quality scenes partially fire (their
 * bright smooth upper areas), as does the mildly textured backlitportrait background at a
 * partial level. This is accepted because the ONLY consequence is the mean-preserving chroma
 * smoothing -- on neutral content it removes residual chroma speckle and cannot shift colour
 * (gray stays gray), and it never triggers the blue deepening. OvercastSkyGoldenTest bakes
 * the measured membership ceilings and proves the effect on those scenes is bounded and
 * chroma-only.
 *
 * The raw prior is smoothed with a [BoxBlur] of [DEFAULT_BLUR_RADIUS] and clamped, like the
 * other masks. Spatial support: the texture energy is two sequential box means (2 *
 * [TEXTURE_RADIUS] = 24 px) plus the final mask blur ([DEFAULT_BLUR_RADIUS] = 12 px), well
 * inside the [TiledFinishing] halo (see that class). Pure Kotlin, deterministic, no Android
 * dependencies.
 */
object OvercastSkyMask {

    // --- texture (non-planar detail energy) prior ------------------------------------

    /** Radius of both box-mean passes of the detail-energy measure. */
    const val TEXTURE_RADIUS = 12

    /** Detail-energy smoothstep knees (code^2): full smoothness at/below [VAR_LO], rejected
     *  as textured at/above [VAR_HI]. Tuned on the fixture measurements in the class doc. */
    const val VAR_LO = 12.0
    const val VAR_HI = 36.0

    // --- bright prior -----------------------------------------------------------------

    /** Luma smoothstep knees: overcast sky is bright even when gray; no dusk floor. */
    const val LUMA_LO = 140.0
    const val LUMA_HI = 180.0

    // --- neutrality prior ---------------------------------------------------------------

    /** Chroma-magnitude smoothstep knees: fully neutral at/below [CHROMA_LO], rejected
     *  (coloured -- blue sky, walls, skin) at/above [CHROMA_HI]. */
    const val CHROMA_LO = 6.0
    const val CHROMA_HI = 12.0

    /** Spatial smoothing radius of the mask (coherent regions, no per-pixel flicker). */
    const val DEFAULT_BLUR_RADIUS = 12

    // Rec. 601 luma weights, matching [Luminance] / [SkyMask] / [ChromaDenoiser].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Overcast-sky-likelihood prior of [frame] in [0, 1], one value per pixel (row-major),
     * spatially smoothed with a box blur of [blurRadius]. The vertical position prior uses
     * ABSOLUTE image rows exactly as [SkyMask.compute] does: [rowOffset] is the frame's first
     * row within the full image and [imageHeight] the full-image height (defaults cover the
     * whole-frame case). Dark, textured, or coloured content yields exactly 0.
     *
     * [luma] may supply the frame's precomputed Rec. 601 luma plane (issue #113: the
     * finishing paths extract it once from the denoised state and share it across every
     * mask), sparing BOTH of this prior's RGB -> Y passes -- the [detailEnergy] plane and
     * the per-pixel bright/neutrality derivations; null derives luma internally. Same
     * weights and expression order either way, so the result is bit-identical (see
     * SharedLumaPlaneTest).
     */
    fun compute(
        frame: Frame,
        blurRadius: Int = DEFAULT_BLUR_RADIUS,
        rowOffset: Int = 0,
        imageHeight: Int = frame.height,
        luma: DoubleArray? = null,
    ): DoubleArray {
        val src = frame.argb
        require(luma == null || luma.size == src.size) { "luma must have one value per pixel" }
        val energy = detailEnergy(frame, TEXTURE_RADIUS, luma)
        val width = frame.width
        val raw = DoubleArray(src.size)
        // Per-pixel priors are element-wise (row-parallel); the box blurs are parallel
        // internally and bit-identical to the serial path.
        PipelineParallel.parallelRows(frame.height) { start, end ->
            for (y in start until end) {
                val rowFraction = (rowOffset + y).toDouble() / imageHeight
                val position = SkyMask.positionWeight(rowFraction)
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val pixel = src[i]
                    val r = ((pixel shr 16) and 0xFF).toDouble()
                    val g = ((pixel shr 8) and 0xFF).toDouble()
                    val b = (pixel and 0xFF).toDouble()
                    val yLuma = luma?.get(i) ?: (R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b)
                    raw[i] = texturePrior(energy[i]) * brightPriorForLuma(yLuma) *
                        neutralityPriorForLuma(r, b, yLuma) * position
                }
            }
        }
        val smoothed = BoxBlur.blur(raw, width, frame.height, blurRadius)
        // The box mean of values in [0, 1] is already in [0, 1]; clamp only to defend against
        // floating-point drift so downstream modulation stays strictly bounded.
        for (i in smoothed.indices) smoothed[i] = smoothed[i].coerceIn(0.0, 1.0)
        return smoothed
    }

    /**
     * The local NON-PLANAR luma detail energy of [frame] at [radius], one value per pixel
     * (code^2): the box mean of the squared deviation of luma from its own box mean. Exactly
     * 0 on flat content and ~0 in the interior of any linear ramp (the box mean of a plane
     * is the plane), high on textured surfaces. Exposed for direct testing of the measure.
     * [luma] may supply the frame's precomputed Rec. 601 luma plane (issue #113, read-only
     * here); null derives it internally, bit-identically.
     */
    fun detailEnergy(frame: Frame, radius: Int = TEXTURE_RADIUS, luma: DoubleArray? = null): DoubleArray {
        val src = frame.argb
        require(luma == null || luma.size == src.size) { "luma must have one value per pixel" }
        val lumaPlane = luma ?: DoubleArray(src.size).also { extracted ->
            PipelineParallel.parallelRows(src.size) { start, end ->
                for (i in start until end) {
                    val pixel = src[i]
                    val r = ((pixel shr 16) and 0xFF).toDouble()
                    val g = ((pixel shr 8) and 0xFF).toDouble()
                    val b = (pixel and 0xFF).toDouble()
                    extracted[i] = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                }
            }
        }
        val mean = BoxBlur.blur(lumaPlane, frame.width, frame.height, radius)
        val squared = DoubleArray(src.size)
        for (i in squared.indices) {
            val d = lumaPlane[i] - mean[i]
            squared[i] = d * d
        }
        return BoxBlur.blur(squared, frame.width, frame.height, radius)
    }

    /** The falling texture prior in [0, 1] for a detail [energy] in code^2: 1 at/below
     *  [VAR_LO] (smooth = sky-like), 0 at/above [VAR_HI] (textured). */
    fun texturePrior(energy: Double): Double = 1.0 - smoothstep(VAR_LO, VAR_HI, energy)

    /** The bright prior in [0, 1]: 0 at/below [LUMA_LO], 1 at/above [LUMA_HI]. No dusk
     *  floor -- a dark gray region carries no evidence of being sky. */
    fun brightPrior(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        return brightPriorForLuma(y)
    }

    /** [brightPrior] with the pixel's luma [y] already extracted (shared plane, issue #113). */
    private fun brightPriorForLuma(y: Double): Double = smoothstep(LUMA_LO, LUMA_HI, y)

    /** The neutrality prior in [0, 1]: 1 for near-zero opponent chroma magnitude, 0 at/above
     *  [CHROMA_HI] -- rejecting blue sky (handled by [SkyMask]), coloured walls and skin. */
    fun neutralityPrior(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        return neutralityPriorForLuma(r, b, y)
    }

    /** [neutralityPrior] with the pixel's luma [y] already extracted (shared plane, issue #113). */
    private fun neutralityPriorForLuma(r: Double, b: Double, y: Double): Double {
        val cr = r - y
        val cb = b - y
        return 1.0 - smoothstep(CHROMA_LO, CHROMA_HI, sqrt(cr * cr + cb * cb))
    }

    /** Classic smoothstep: 0 for x <= e0, 1 for x >= e1, C1-smooth in between. */
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
