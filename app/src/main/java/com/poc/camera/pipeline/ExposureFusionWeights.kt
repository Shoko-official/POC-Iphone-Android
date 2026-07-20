package com.poc.camera.pipeline

import kotlin.math.exp

/**
 * Per-pixel fusion weights for Mertens-style exposure fusion.
 *
 * The core term is a well-exposedness Gaussian on the pixel's luma, peaking at
 * mid-grey and falling off towards black and white:
 *
 *   w_e(v) = exp( -((v / 255 - 0.5)^2) / (2 * sigma^2) ),  sigma = [WELL_EXPOSED_SIGMA]
 *
 * where v is the Rec. 601 luma (same weights as [Luminance]). A pixel rendered near
 * mid-grey in a given exposure is trusted; one near either extreme is not.
 *
 * On top of that an optional saturation-avoidance term hard-zeroes a pixel whose
 * channels are unreliable in its exposure:
 *  - in an OVER-exposed frame (relative EV > 0, i.e. the brighter capture) a channel
 *    at or above [CLIP_HIGH] is a blown highlight and cannot be recovered, so the
 *    pixel's weight is forced to 0;
 *  - in an UNDER-exposed frame (relative EV < 0, the darker capture) a channel at or
 *    below [CLIP_LOW] is a crushed shadow carrying only noise, so the weight is 0;
 *  - an EV 0 frame gets no saturation penalty (it is the reference exposure).
 *
 * The final weight is `w_e(luma) * saturationTerm`, where the saturation term is 1.0
 * (keep) or 0.0 (drop). Weights are intentionally allowed to be exactly 0; the
 * epsilon floor that keeps normalisation well-defined lives in [ExposureFusion].
 *
 * Pure Kotlin, deterministic, no Android dependencies.
 */
object ExposureFusionWeights {

    /** Standard deviation of the well-exposedness Gaussian, in normalised [0, 1] luma. */
    const val WELL_EXPOSED_SIGMA = 0.2

    /** A channel at or above this in an over-exposed frame is treated as clipped. */
    const val CLIP_HIGH = 250

    /** A channel at or below this in an under-exposed frame is treated as crushed. */
    const val CLIP_LOW = 5

    /**
     * Well-exposedness weight for a single luma value [luma] in [0, 255].
     * Symmetric around 127.5 and always in (0, 1].
     */
    fun wellExposedness(luma: Double): Double {
        val z = luma / 255.0 - 0.5
        return exp(-(z * z) / (2.0 * WELL_EXPOSED_SIGMA * WELL_EXPOSED_SIGMA))
    }

    /**
     * Per-pixel weight map for [frame] captured at relative exposure [ev]. The
     * well-exposedness term is multiplied by the saturation-avoidance term described
     * in the class docs. Length equals the pixel count.
     */
    fun weightMap(frame: Frame, ev: Double): DoubleArray {
        val argb = frame.argb
        val out = DoubleArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            var weight = wellExposedness(luma)
            if (ev > 0.0) {
                // Over-exposed capture: drop blown highlights.
                if (r >= CLIP_HIGH || g >= CLIP_HIGH || b >= CLIP_HIGH) weight = 0.0
            } else if (ev < 0.0) {
                // Under-exposed capture: drop crushed shadows.
                if (r <= CLIP_LOW || g <= CLIP_LOW || b <= CLIP_LOW) weight = 0.0
            }
            out[i] = weight
        }
        return out
    }
}
