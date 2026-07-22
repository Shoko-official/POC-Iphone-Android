package com.poc.camera.pipeline

/**
 * Per-pixel SKY-likelihood prior in [0, 1], the sky half of the semantic-region rendering
 * stage (issue #98). Where [SkinMask] only ever BOUNDS the generic operators inside skin,
 * this prior instead DRIVES a region-targeted rendition boost: [SemanticRendering] uses it
 * to deepen blue and reduce chroma noise inside sky regions (see that class). It is a
 * likelihood, not a segmentation -- a soft, conservative membership tuned to fire on clear
 * blue-cyan sky and to stay near zero on everything else.
 *
 * ## Three multiplicative priors, in the Rec. 601 opponent chroma space
 *
 * The mask combines three independent priors, each in [0, 1], multiplied together so ALL
 * must agree for a pixel to read as sky. The chroma priors use the same simple opponent
 * space as [ChromaDenoiser] / [ChromaRollOff] (documented axes, not a calibrated YCbCr):
 *
 *   Y  = 0.299R + 0.587G + 0.114B      // luma
 *   Cb = B - Y                         // blue-difference chroma  (positive for blue)
 *   Cr = R - Y                         // red-difference chroma    (negative for blue/cyan)
 *
 *  1. CHROMA prior -- a smooth blue-cyan SECTOR membership (the sector analog of
 *     [SkinMask]'s ellipse: a smooth chroma-cluster membership rather than a hard mask).
 *     Clear sky sits where Cb is notably POSITIVE (blue) and Cr is NEGATIVE-TO-NEUTRAL, so
 *     the membership is the product of a rising smoothstep on Cb (rewards blue) and a
 *     falling smoothstep on Cr (rejects the red/magenta/purple that share a positive Cb).
 *     Greens (Cb negative) and reds (Cb negative) both score 0 on the Cb term. A neutral
 *     pixel (Cb = Cr = 0) scores 0 on the Cb term, so a grayscale frame yields an all-zero
 *     mask -- which keeps the whole semantic stage a strict no-op on every grayscale scene.
 *  2. LUMA prior -- sky is bright, so a smoothstep rewards luma above ~[LUMA_HI]. It does
 *     NOT floor to zero in the shadows: a [LUMA_FLOOR] keeps dusk/low-key skies at moderate
 *     weight rather than excluding them, since a warm dusk sky is dark but still sky.
 *  3. POSITION prior -- sky is predominantly in the UPPER image, so a vertical ramp weights
 *     the top of the frame fully and decays downward: full at the top, ~[POSITION_MID_WEIGHT]
 *     by [POSITION_MID_FRACTION] of the height, and 0 below [POSITION_ZERO_FRACTION].
 *
 * ### Assumption + limitation of the position prior (documented by design)
 *
 * The position prior encodes the assumption that sky lives in the upper image. Its
 * deliberate consequence is that a bright blue SKY REFLECTION in water (or any blue in the
 * lower image) is EXCLUDED even though its chroma and luma read as sky -- that is by design,
 * not a bug: treating a reflection as sky would deepen and smooth the wrong region. It also
 * means an unusual composition (sky at the bottom) is under-served; the prior is tuned for
 * the common case. The position prior is a function of ABSOLUTE image row, so [compute]
 * takes the tile's [rowOffset] and the full [imageHeight]: a [TiledFinishing] tile computes
 * the same weight the whole frame would at that row (the chroma/luma priors are local to the
 * tile, but the geometric prior must know where the tile sits in the full image).
 *
 * ### Overcast / gray sky limitation (documented by design)
 *
 * A gray or overcast sky has near-neutral chroma, so it FAILS the blue chroma prior and
 * reads ~0. This is accepted for this stage: a robust overcast prior would need a luminance-
 * texture cue (flat, bright, textureless upper region) rather than colour, which is left as
 * future work. The conservative failure mode -- a gray sky is simply not boosted -- is the
 * safe one.
 *
 * The raw per-pixel prior is smoothed with a [BoxBlur] of [DEFAULT_BLUR_RADIUS] and clamped,
 * so the boost applies over coherent regions rather than flickering per pixel. Pure Kotlin,
 * deterministic, no Android dependencies.
 */
object SkyMask {

    // --- chroma (blue-cyan sector) prior -------------------------------------------

    /** Cb smoothstep knees (opponent B - Y): no blue at/below [CB_LO], full blue at/above [CB_HI]. */
    const val CB_LO = 8.0
    const val CB_HI = 30.0

    /** Cr smoothstep knees (opponent R - Y): full sky at/below [CR_LO], rejected at/above [CR_HI]
     *  (positive Cr is the red/magenta/purple direction a blue Cb alone would let through). */
    const val CR_LO = 6.0
    const val CR_HI = 26.0

    // --- luma prior ----------------------------------------------------------------

    /** Luma smoothstep knees: sky reads brighter toward [LUMA_HI]. */
    const val LUMA_LO = 90.0
    const val LUMA_HI = 150.0

    /** Floor of the luma prior: a dark dusk sky keeps this weight rather than being excluded. */
    const val LUMA_FLOOR = 0.4

    // --- position prior ------------------------------------------------------------

    /** Height fraction at which the vertical prior has decayed to [POSITION_MID_WEIGHT]. */
    const val POSITION_MID_FRACTION = 0.60

    /** The vertical prior's weight at [POSITION_MID_FRACTION] (full = 1.0 at the top). */
    const val POSITION_MID_WEIGHT = 0.30

    /** Height fraction at/below which the vertical prior is 0 (nothing this low reads as sky). */
    const val POSITION_ZERO_FRACTION = 0.75

    /** Spatial smoothing radius of the mask (coherent regions, no per-pixel flicker). */
    const val DEFAULT_BLUR_RADIUS = 12

    // Rec. 601 luma weights, matching [Luminance] / [ChromaDenoiser] / [ChromaRollOff].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Sky-likelihood prior of [frame] in [0, 1], one value per pixel (row-major), spatially
     * smoothed with a box blur of [blurRadius]. The vertical position prior uses ABSOLUTE
     * image rows: [rowOffset] is the frame's first row within the full image and [imageHeight]
     * the full-image height, so a tiled finish reproduces the whole-frame weight at each row
     * (defaults cover the whole-frame case). A grayscale frame yields an all-zero result.
     */
    fun compute(
        frame: Frame,
        blurRadius: Int = DEFAULT_BLUR_RADIUS,
        rowOffset: Int = 0,
        imageHeight: Int = frame.height,
    ): DoubleArray {
        val src = frame.argb
        val width = frame.width
        val raw = DoubleArray(src.size)
        // Per-pixel prior is element-wise (row-parallel); the box blur below is parallel
        // internally and its output is bit-identical to the serial path.
        PipelineParallel.parallelRows(frame.height) { start, end ->
            for (y in start until end) {
                val rowFraction = (rowOffset + y).toDouble() / imageHeight
                val position = positionWeight(rowFraction)
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val pixel = src[i]
                    val r = ((pixel shr 16) and 0xFF).toDouble()
                    val g = ((pixel shr 8) and 0xFF).toDouble()
                    val b = (pixel and 0xFF).toDouble()
                    raw[i] = chromaLikelihood(r, g, b) * lumaPrior(r, g, b) * position
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
     * The pure blue-cyan chroma-sector membership in [0, 1] (no luma or position gate):
     * a rising smoothstep on Cb (blue) times a falling smoothstep on Cr (rejecting the
     * red/magenta a positive Cb alone would admit). Exposed for direct testing of the rule.
     */
    fun chromaLikelihood(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        val cb = b - y
        val cr = r - y
        val blue = smoothstep(CB_LO, CB_HI, cb)
        val notWarm = 1.0 - smoothstep(CR_LO, CR_HI, cr)
        return blue * notWarm
    }

    /** The luma prior in [[LUMA_FLOOR], 1]: bright skies read full, dusk skies keep the floor. */
    fun lumaPrior(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        return LUMA_FLOOR + (1.0 - LUMA_FLOOR) * smoothstep(LUMA_LO, LUMA_HI, y)
    }

    /**
     * The vertical position prior for a normalised image-row fraction [f] in [0, 1): 1.0 at
     * the top, linearly decaying to [POSITION_MID_WEIGHT] at [POSITION_MID_FRACTION], then to
     * 0 at [POSITION_ZERO_FRACTION] and 0 below. Exposed for direct testing of the ramp.
     */
    fun positionWeight(f: Double): Double = when {
        f <= 0.0 -> 1.0
        f < POSITION_MID_FRACTION ->
            1.0 - (1.0 - POSITION_MID_WEIGHT) * (f / POSITION_MID_FRACTION)
        f < POSITION_ZERO_FRACTION ->
            POSITION_MID_WEIGHT * (1.0 - (f - POSITION_MID_FRACTION) / (POSITION_ZERO_FRACTION - POSITION_MID_FRACTION))
        else -> 0.0
    }

    /** Classic smoothstep: 0 for x <= e0, 1 for x >= e1, C1-smooth in between. */
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
