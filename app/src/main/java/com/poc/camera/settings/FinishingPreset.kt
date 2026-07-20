package com.poc.camera.settings

import com.poc.camera.pipeline.FinishingParams

/**
 * User-facing rendition choice for merged burst photos. Kept in the settings layer
 * (not [com.poc.camera.pipeline]) so the pipeline stays free of UI concepts; [params]
 * is the only bridge between the user's choice and [FinishingParams].
 *
 * Each preset maps to a tested finishing profile rather than a hand-picked, unverified
 * one:
 *  - [Natural] is exactly [FinishingParams.RENDITION], the profile
 *    `RenditionGoldenRegressionTest` already scores against the rendition axis target -
 *    the deliberate "meaningful rendition" look referenced from issue #46.
 *  - [Vivid] takes [FinishingParams.RENDITION] and nudges it further along the same
 *    rendition axis: more saturation, more global contrast and a stronger local-contrast
 *    pass. The steps stay bounded and modest (comparable in size to the DEFAULT ->
 *    RENDITION delta) so it reads as "punchier Natural", not a different pipeline.
 *  - [Detail] takes [FinishingParams.RENDITION] and leans texture-forward instead:
 *    a stronger detail-enhance pass with a slightly softer local-contrast trade-off and
 *    a restrained (near-Natural) saturation bump, so extra sharpness doesn't also read
 *    as extra colour.
 */
enum class FinishingPreset {
    Natural,
    Vivid,
    Detail;

    val params: FinishingParams
        get() = when (this) {
            Natural -> FinishingParams.RENDITION
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
