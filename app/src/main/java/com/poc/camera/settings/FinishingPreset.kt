package com.poc.camera.settings

import com.poc.camera.pipeline.FinishingParams

/**
 * User-facing rendition choice for merged burst photos. Kept in the settings layer
 * (not [com.poc.camera.pipeline]) so the pipeline stays free of UI concepts; [params]
 * is the only bridge between the user's choice and [FinishingParams].
 *
 * Each preset maps to a tested finishing profile rather than a hand-picked, unverified
 * one:
 *  - [Natural] is exactly [FinishingParams.REFERENCE], the reference-matched profile whose
 *    constants were FITTED against a real iPhone rendition of the same scene (issue #99).
 *    Natural is the DEFAULT preset and the look the user judges against an iPhone, so it maps
 *    to "the closest to reference-class rendition we have measured" rather than to the
 *    restrained [FinishingParams.RENDITION] baseline the rendition axis is anchored on. Its
 *    behaviour is gated by ReferenceProfileGoldenTest.
 *  - [Vivid] takes [FinishingParams.RENDITION] and nudges it further along the same
 *    rendition axis: more saturation, more global contrast and a stronger local-contrast
 *    pass. The steps stay bounded and modest (comparable in size to the DEFAULT ->
 *    RENDITION delta) so it reads as "punchier Natural", not a different pipeline.
 *  - [Detail] takes [FinishingParams.RENDITION] and leans texture-forward instead:
 *    a stronger detail-enhance pass with a slightly softer local-contrast trade-off and
 *    a restrained (near-Natural) saturation bump, so extra sharpness doesn't also read
 *    as extra colour.
 *
 * Vivid and Detail stay derived from [FinishingParams.RENDITION] (not [FinishingParams.REFERENCE])
 * so their tuned deltas are unchanged; only the default Natural look moves to the fitted profile.
 */
enum class FinishingPreset {
    Natural,
    Vivid,
    Detail;

    val params: FinishingParams
        get() = when (this) {
            Natural -> FinishingParams.REFERENCE
            Vivid -> FinishingParams.RENDITION.copy(
                saturation = 1.16,
                contrast = 1.09,
                localContrast = 0.45,
            )
            Detail -> FinishingParams.RENDITION.copy(
                detailEnhance = 0.8,
                localContrast = 0.40,
                saturation = 1.04,
            )
        }
}
