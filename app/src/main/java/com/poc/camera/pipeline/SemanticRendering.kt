package com.poc.camera.pipeline

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parameters for [SemanticRendering] (issue #98). Every gain is bounded so the stage can
 * never over-cook a region: the deliberate per-channel shift (chroma deepening + foliage
 * luma lift) is capped at [SKY_CHROMA_SHIFT] / ([FOLIAGE_CHROMA_SHIFT] + [FOLIAGE_LUMA_LIFT])
 * codes at full mask and strength -- i.e. <= 12 codes in every region -- independent of the
 * gains, so a future gain increase cannot break the bound.
 *
 *  - [strength] master blend in [0, 1] (0 = the stage is a bit-exact passthrough). Scales
 *    every adjustment, so [FinishingParams.semanticRendering] dials the whole effect.
 *  - [skySatGain] / [foliageSatGain] the fractional chroma-magnitude deepening at full mask
 *    (before the shift cap): sky blue is deepened, foliage green enriched.
 *  - [skySmoothStrength] the in-sky blend toward the luma-guided smoothed chroma, at
 *    [skySmoothRadius]: skies show chroma noise most, so this reduces it (and smooths gradient
 *    banding) inside the sky masks -- blue ([SkyMask]) OR overcast ([OvercastSkyMask]) --
 *    while leaving the rest of the frame untouched.
 *  - [foliageLumaLift] the additive luma lift (codes) for foliage at full mask: vegetation is
 *    often a touch underexposed, so its low-mid tones are lifted slightly.
 *
 * The chroma deepening scales all opponent chroma components by ONE factor, so it moves
 * chroma MAGNITUDE along the existing hue and never rotates hue (see [SemanticRendering]).
 * Deterministic, no Android dependencies.
 */
data class SemanticRenderingParams(
    val strength: Double = 1.0,
    val skySatGain: Double = 0.14,
    val skySmoothStrength: Double = 0.7,
    val skySmoothRadius: Int = 8,
    val foliageSatGain: Double = 0.16,
    val foliageLumaLift: Double = 4.0,
) {
    init {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        require(skySatGain >= 0.0) { "skySatGain must be >= 0" }
        require(skySmoothStrength in 0.0..1.0) { "skySmoothStrength must be in [0, 1]" }
        require(skySmoothRadius >= 0) { "skySmoothRadius must be >= 0" }
        require(foliageSatGain >= 0.0) { "foliageSatGain must be >= 0" }
        require(foliageLumaLift >= 0.0) { "foliageLumaLift must be >= 0" }
    }

    companion object {
        /** Max deliberate chroma-magnitude shift (codes) inside sky at full mask+strength. */
        const val SKY_CHROMA_SHIFT = 12.0

        /** Max deliberate chroma-magnitude shift (codes) inside foliage at full mask+strength. */
        const val FOLIAGE_CHROMA_SHIFT = 8.0

        /** Max luma lift (codes) inside foliage at full mask+strength. [FOLIAGE_CHROMA_SHIFT]
         *  + this is the foliage per-channel bound (12), matching the sky bound. */
        const val FOLIAGE_LUMA_LIFT = 4.0

        /** Guided-filter edge regulariser for the sky chroma smooth, in squared luma units --
         *  same "~3% of full scale is flat" knee as [ChromaDenoiser], so real chroma edges that
         *  co-occur with a luma edge survive while flat-sky speckle is flattened. */
        val SKY_SMOOTH_EPS = (0.03 * 255.0) * (0.03 * 255.0)

        val DEFAULT = SemanticRenderingParams()
    }
}

/**
 * Semantic-region rendering: the stage that separates an iPhone-class look from a global-only
 * pipeline (issue #98). Skin is already handled by [SkinMask] (as a protection prior); this
 * stage adds SKY and FOLIAGE as rendition BOOSTS, region-targeted by [SkyMask] /
 * [OvercastSkyMask] / [FoliageMask]:
 *
 *  - SKY: a bounded blue DEEPENING plus a luma-guided chroma SMOOTHING (noise + gradient-band
 *    reduction) inside the sky masks -- skies carry the most visible chroma noise. The two
 *    sky priors drive DIFFERENT subsets of that treatment (issue #106): the chroma smoothing
 *    is weighted by `max(blueSky, overcastSky)`, so a gray overcast sky is denoised and
 *    de-banded like a blue one, but the blue deepening is weighted by the BLUE prior ONLY --
 *    an overcast sky must stay gray, and the smoothing is mean-preserving, so a pure-overcast
 *    region receives no colour shift whatsoever.
 *  - FOLIAGE: a bounded green ENRICHMENT plus a small luma (shadow) lift inside the foliage
 *    mask -- vegetation is often a touch underexposed.
 *
 * ## Where it sits, and why it is the modulation plane "inverted"
 *
 * [SkinMask] feeds a modulation plane that REDUCES operator strength inside skin. This stage
 * is the mirror image: the masks drive per-pixel BOOSTS that are ZERO outside the mask and
 * grow with it, so ground/skin/neutral content (mask ~ 0) is left bit-exactly untouched.
 * [FinishingPipeline] runs it LAST, as the final colour stage AFTER [ChromaRollOff]: the
 * roll-off is a whole-frame chroma shoulder that compresses everything past its knee, and a
 * blue sky sits above that knee, so running the deepening before the roll-off would let the
 * shoulder compress the deliberate boost right back out. Running the region boost after the
 * roll-off makes it the final, deliberate word on sky/foliage colour, while the roll-off still
 * tames the isolated extreme chroma the earlier global stages produced.
 *
 * ## Bounded and hue-stable
 *
 * All work is in the Rec. 601 opponent space (`Y`, `Cr = R - Y`, `Cb = B - Y`, `Cg = G - Y`).
 * The chroma smoothing (sky) blends the two chroma planes toward their luma-guided filtered
 * values -- mean-preserving, so it reduces noise/banding without shifting the region's mean
 * colour. The chroma deepening then scales `Cr`, `Cb`, `Cg` by a SINGLE factor, so it changes
 * chroma MAGNITUDE along the existing hue and never rotates hue (up to 8-bit rounding). The
 * deliberate per-channel shift (deepening + foliage luma lift) is CAPPED per region -- the
 * chroma-magnitude shift by [SemanticRenderingParams.SKY_CHROMA_SHIFT] /
 * [SemanticRenderingParams.FOLIAGE_CHROMA_SHIFT], the luma lift by
 * [SemanticRenderingParams.FOLIAGE_LUMA_LIFT] -- so it is <= 12 codes per channel at full
 * mask+strength regardless of the gains. Luma (`Y`) is preserved except the explicit foliage
 * lift.
 *
 * ## Determinism / parallelism
 *
 * The opponent split, the deepening/reconstruction and the mask blend are all element-wise
 * (row-parallel, bit-identical across chunk counts); the sky chroma smoothing is the shared
 * [GuidedFilter], itself bit-identical across chunk counts. Under [TiledFinishing] the masks
 * are computed per tile on the denoised tile (blur radius <= the chain support) and the sky
 * smoothing reads within [SemanticRenderingParams.skySmoothRadius], so a tile core reproduces
 * the whole-frame result up to the documented [BoxBlur] running-sum drift (the tile halo is
 * sized to cover this stage's support). At [SemanticRenderingParams.strength] <= 0 the input
 * frame is returned unchanged (same reference, bit-exact).
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object SemanticRendering {

    // Rec. 601 luma weights, matching [Luminance] / [ChromaDenoiser] / [ChromaRollOff].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    /**
     * Returns [frame] with sky/foliage rendering applied, driven by [skyMask] (blue sky,
     * typically [SkyMask.compute]), [overcastSkyMask] (gray sky, typically
     * [OvercastSkyMask.compute]) and [foliageMask] (typically [FoliageMask.compute]), each
     * row-major with one value per pixel in [0, 1]. The chroma smoothing is weighted by
     * `max(skyMask, overcastSkyMask)`; the blue deepening by [skyMask] alone (a gray sky
     * must stay gray -- see the class doc). At [SemanticRenderingParams.strength] <= 0 the
     * frame is returned unchanged (bit-exact passthrough). Alpha is forced opaque.
     *
     * @param chunkCount row-parallel chunk count for the element-wise passes;
     *   [PipelineParallel.SERIAL_CHUNKS] forces the serial reference path.
     */
    fun apply(
        frame: Frame,
        skyMask: DoubleArray,
        overcastSkyMask: DoubleArray,
        foliageMask: DoubleArray,
        params: SemanticRenderingParams = SemanticRenderingParams.DEFAULT,
        chunkCount: Int = PipelineParallel.parallelism,
    ): Frame {
        if (params.strength <= 0.0) return frame
        val src = frame.argb
        val n = src.size
        require(skyMask.size == n) { "skyMask must have one value per pixel" }
        require(overcastSkyMask.size == n) { "overcastSkyMask must have one value per pixel" }
        require(foliageMask.size == n) { "foliageMask must have one value per pixel" }

        // Opponent-space split (element-wise, row-parallel).
        val luma = DoubleArray(n)
        val cb = DoubleArray(n)
        val cr = DoubleArray(n)
        PipelineParallel.parallelRows(n, chunkCount) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val r = ((pixel shr 16) and 0xFF).toDouble()
                val g = ((pixel shr 8) and 0xFF).toDouble()
                val b = (pixel and 0xFF).toDouble()
                val y = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
                luma[i] = y
                cb[i] = b - y
                cr[i] = r - y
            }
        }

        // Sky chroma smoothing: luma-guided filtering of the two chroma planes, blended in
        // only where the sky mask fires. Computed once; a no-op when its strength is 0.
        val smoothSky = params.skySmoothStrength > 0.0
        val cbFiltered = if (smoothSky) {
            GuidedFilter.guided(cb, luma, frame.width, frame.height, params.skySmoothRadius, SemanticRenderingParams.SKY_SMOOTH_EPS, chunkCount)
        } else {
            cb
        }
        val crFiltered = if (smoothSky) {
            GuidedFilter.guided(cr, luma, frame.width, frame.height, params.skySmoothRadius, SemanticRenderingParams.SKY_SMOOTH_EPS, chunkCount)
        } else {
            cr
        }

        val strength = params.strength
        val out = IntArray(n)
        PipelineParallel.parallelRows(n, chunkCount) { start, end ->
            for (i in start until end) {
                val sky = skyMask[i]
                val fol = foliageMask[i]
                val y = luma[i]
                // 1. Sky chroma smoothing (mean-preserving noise/banding reduction) blended by
                //    EITHER sky prior -- blue or overcast -- and the smoothing strength. The
                //    overcast prior stops here: it never feeds the deepening below, so a gray
                //    sky is denoised without any colour shift.
                val skySmooth = strength * params.skySmoothStrength * max(sky, overcastSkyMask[i])
                var chB = cb[i] + skySmooth * (cbFiltered[i] - cb[i])
                var chR = cr[i] + skySmooth * (crFiltered[i] - cr[i])
                // 2. Bounded chroma deepening: a single scale on both chroma components (so hue
                //    is preserved), with the chroma-magnitude shift capped per region.
                val extraGain = strength * (params.skySatGain * sky + params.foliageSatGain * fol)
                val shiftCap = strength *
                    (SemanticRenderingParams.SKY_CHROMA_SHIFT * sky + SemanticRenderingParams.FOLIAGE_CHROMA_SHIFT * fol)
                val mag = sqrt(chR * chR + chB * chB)
                if (mag > 1e-6 && extraGain > 0.0) {
                    val targetMag = (mag * (1.0 + extraGain)).coerceAtMost(mag + shiftCap)
                    val scale = targetMag / mag
                    chB *= scale
                    chR *= scale
                }
                // 3. Foliage luma (shadow) lift, bounded.
                val adjY = y + strength * params.foliageLumaLift * fol
                // Reconstruct RGB from the adjusted luma + chroma (luma preserved except the lift;
                // Cg follows Cr/Cb, so scaling both preserved hue).
                val nr = (adjY + chR).roundToInt().coerceIn(0, 255)
                val nb = (adjY + chB).roundToInt().coerceIn(0, 255)
                val ng = (adjY - (R_WEIGHT * chR + B_WEIGHT * chB) / G_WEIGHT).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }
}
