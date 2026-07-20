package com.poc.camera.pipeline

import kotlin.math.sqrt

/**
 * Per-pixel skin-likelihood prior in [0, 1], used by [FinishingPipeline] to BOUND (never
 * add to) how aggressively the generic rendition operators -- saturation, local tone
 * mapping and sharpening -- act inside skin regions. It only ever LIMITS operator
 * strength; it never lightens, darkens or shifts skin hue, so it is a protection prior,
 * not a "beautification".
 *
 * ## Rule: a smooth chroma-cluster membership in JFIF YCbCr
 *
 * Skin colours across the whole human range form a compact, well-studied cluster in the
 * chroma (Cb, Cr) plane of full-range Rec. 601 / JFIF YCbCr. The cluster location is
 * classically bounded by Cb in ~[77, 127] and Cr in ~[133, 173] (Chai & Ngan, "Face
 * segmentation using skin-color map in videophone applications", IEEE T-CSVT 1999), and
 * is commonly modelled as an ELLIPSE in that plane (Hsu, Abdel-Mottaleb & Jain, "Face
 * detection in color images", IEEE T-PAMI 2002). This class uses that single,
 * well-established rule: a smooth elliptical membership centred on the skin cluster.
 *
 * The decisive fairness property -- and the reason a chroma-only rule works across the
 * Fitzpatrick range -- is that skin tones from the lightest to the deepest differ mainly
 * in LUMINANCE (Y), not in chroma: they share the same reddish-orange hue direction
 * (Cr elevated above 128, Cb depressed below it). Deep skin is not "grey"; it keeps that
 * hue at a lower Y. So an ellipse in (Cb, Cr) that ignores Y fires on light, medium and
 * deep skin alike, while a luma gate would wrongly exclude deep skin -- which is exactly
 * why the only luma exclusion here is a near-black floor (see below), never a ceiling.
 *
 * JFIF (full-range) conversion, matching the Rec. 601 luma weights used elsewhere:
 *
 *   Y  = 0.299R + 0.587G + 0.114B
 *   Cb = 128 - 0.168736R - 0.331264G + 0.5B
 *   Cr = 128 + 0.5R - 0.418688G - 0.081312B
 *
 * Membership is the normalised ellipse radius mapped through a smoothstep, so it is a
 * SMOOTH prior in [0, 1] rather than a hard binary mask:
 *
 *   rho     = sqrt(((Cb - CB0)/SB)^2 + ((Cr - CR0)/SR)^2)   // 0 at cluster centre
 *   chroma  = 1 - smoothstep(RHO_INNER, RHO_OUTER, rho)     // 1 inside, 0 outside
 *
 * A neutral pixel (R = G = B) has Cb = Cr = 128, which sits well outside the ellipse
 * (rho ~ 2.4 > [RHO_OUTER]), so [chromaLikelihood] returns EXACTLY 0 there: a grayscale
 * frame yields an all-zero mask, which is what keeps skin protection a strict no-op on
 * every grayscale scene (see [FinishingPipeline]).
 *
 * ## Luma gate: exclude only near-black
 *
 * Below ~8 codes chroma is dominated by noise and quantisation and carries no reliable
 * hue, so the prior is smoothly ramped down to 0 there ([BLACK_LO]..[BLACK_HI]). There is
 * deliberately NO upper luma gate: excluding bright pixels would not hurt fairness, but
 * excluding dark ones WOULD wrongly drop deep skin tones, so the floor is kept as low as
 * possible and no ceiling is applied.
 *
 * ## Spatial smoothing
 *
 * The raw per-pixel prior is smoothed with a [BoxBlur] of radius [DEFAULT_BLUR_RADIUS] and
 * re-clamped to [0, 1], so protection applies over COHERENT regions rather than flickering
 * per pixel on individual chroma-noisy samples. The blur support (2 * radius = 16 px) is
 * well under the [TiledFinishing] overlap, so a tiled finish computes a bit-identical mask
 * core per tile.
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object SkinMask {

    /** Skin-cluster ellipse centre in (Cb, Cr), tuned to the Fitzpatrick I-VI cluster. */
    const val CB0 = 107.0
    const val CR0 = 152.0

    /** Ellipse radii (Cb, Cr) in chroma code units; rho = 1 is the cluster boundary. */
    const val SB = 14.0
    const val SR = 13.0

    /** Smoothstep knees on the normalised ellipse radius: full skin at/below, none at/above. */
    const val RHO_INNER = 1.0
    const val RHO_OUTER = 2.2

    /** Near-black luma gate: chroma below [BLACK_LO] carries no reliable hue, so the prior
     *  ramps smoothly from 0 (at [BLACK_LO]) to full (at [BLACK_HI]). No upper gate exists,
     *  so deep skin tones are never excluded. */
    const val BLACK_LO = 6.0
    const val BLACK_HI = 14.0

    /** Spatial smoothing radius of the mask (coherent regions, no per-pixel flicker). */
    const val DEFAULT_BLUR_RADIUS = 8

    // Rec. 601 luma weights, matching [Luminance] / [LocalToneMapper] / [ChromaDenoiser].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Skin-likelihood prior of [frame] in [0, 1], one value per pixel (row-major), spatially
     * smoothed with a box blur of [blurRadius]. A grayscale frame yields an all-zero result.
     */
    fun compute(frame: Frame, blurRadius: Int = DEFAULT_BLUR_RADIUS): DoubleArray {
        val src = frame.argb
        val raw = DoubleArray(src.size)
        // Per-pixel prior is element-wise (row-parallel); the box blur below is parallel
        // internally and its output is bit-identical to the serial path.
        PipelineParallel.parallelRows(src.size) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val r = ((pixel shr 16) and 0xFF).toDouble()
                val g = ((pixel shr 8) and 0xFF).toDouble()
                val b = (pixel and 0xFF).toDouble()
                raw[i] = likelihood(r, g, b)
            }
        }
        val smoothed = BoxBlur.blur(raw, frame.width, frame.height, blurRadius)
        // The box mean of values in [0, 1] is already in [0, 1]; clamp only to defend against
        // floating-point drift so downstream modulation stays strictly bounded.
        for (i in smoothed.indices) smoothed[i] = smoothed[i].coerceIn(0.0, 1.0)
        return smoothed
    }

    /**
     * Raw (unsmoothed) skin prior for one RGB triple: the chroma-cluster membership gated by
     * the near-black luma floor. Exposed for direct unit testing of the rule.
     */
    fun likelihood(r: Double, g: Double, b: Double): Double {
        val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        return chromaLikelihood(r, g, b) * lumaGate(y)
    }

    /** The pure chroma-cluster membership (no luma gate), in [0, 1]. */
    fun chromaLikelihood(r: Double, g: Double, b: Double): Double {
        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        val db = (cb - CB0) / SB
        val dc = (cr - CR0) / SR
        val rho = sqrt(db * db + dc * dc)
        return 1.0 - smoothstep(RHO_INNER, RHO_OUTER, rho)
    }

    /** Near-black luma gate: 0 below [BLACK_LO], 1 above [BLACK_HI], smooth between. */
    private fun lumaGate(y: Double): Double = smoothstep(BLACK_LO, BLACK_HI, y)

    /** Classic smoothstep: 0 for x <= e0, 1 for x >= e1, C1-smooth in between. */
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
