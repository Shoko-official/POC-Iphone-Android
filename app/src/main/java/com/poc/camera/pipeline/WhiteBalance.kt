package com.poc.camera.pipeline

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-channel white-balance gains, normalised to green (so [gGain] is always 1.0 and
 * only red and blue are moved). [WhiteBalanceGains.IDENTITY] is the no-op correction.
 */
data class WhiteBalanceGains(
    val rGain: Double,
    val gGain: Double,
    val bGain: Double,
) {
    companion object {
        val IDENTITY = WhiteBalanceGains(1.0, 1.0, 1.0)
    }
}

/**
 * Automatic white balance on a merged burst frame. It estimates the scene illuminant
 * from two complementary cues, converts that estimate into a bounded per-channel gain
 * correction, and applies it to neutralise a colour cast.
 *
 * ## Illuminant estimation (two cues)
 *
 * **1. Robust gray-world.** The classic gray-world assumption is that the average of a
 * scene's surface reflectances is neutral, so the average captured colour equals the
 * illuminant. To keep that assumption honest this cue averages only pixels that are
 * plausibly near-neutral *surfaces*:
 *  - saturation below [graySaturationMax] -- excludes genuinely saturated colours (a
 *    red apple) that would bias the mean, while still admitting a neutral surface that a
 *    cast has tinted (a strong cast lifts a gray to ~0.36 saturation, well under the
 *    threshold);
 *  - luma inside [[grayLumaMin], [grayLumaMax]] -- excludes near-black pixels (where
 *    the ratios are dominated by noise) and near-clipped pixels (where a channel has
 *    saturated the sensor and the ratio is wrong).
 *
 * The per-channel means over the qualifying pixels are **trimmed means**: each channel's
 * qualifying values are sorted and [grayTrimFraction] is dropped from each tail before
 * averaging, so a residual cluster of coloured outliers that slipped under the
 * saturation gate cannot drag the estimate.
 *
 * **2. Highlight-neutral.** Bright near-white surfaces are the best illuminant probes:
 * a specular highlight or a white wall reflects the illuminant almost directly, so its
 * captured colour is the illuminant colour with little surface tint. This cue averages
 * the pixels whose luma falls in the brightest non-clipped percentile band
 * ([highlightPercentileLow]..[highlightPercentileHigh]) with every channel strictly
 * below [highlightChannelMax] (a clipped channel carries no colour information).
 *
 * ## Combining the cues
 *
 * Each cue's mean colour (mR, mG, mB) is turned into green-normalised gains that would
 * neutralise it: `gR = mG / mR`, `gG = 1`, `gB = mG / mB`. The two gain estimates are
 * combined by a weighted average, each weighted by its usable sample count floored at
 * [CUE_WEIGHT_FLOOR] (so a cue that has only a handful of samples still contributes a
 * baseline vote rather than being drowned entirely, yet a cue backed by thousands of
 * pixels is trusted proportionally more). A cue with no usable samples, or whose mean is
 * degenerate (a channel at ~0), is dropped from the average.
 *
 * ## Bounded correction
 *
 * The combined gains are clamped per channel to `[1 / maxGain, maxGain]` (default
 * [DEFAULT_MAX_GAIN], about the strongest tungsten indoor cast), then in [apply] blended
 * toward identity by a `strength` in [0, 1] (`g' = 1 + strength * (g - 1)`), so the
 * correction can be dialled between off and full without changing the estimate.
 *
 * ## Degenerate safety
 *
 * If the total usable sample count (gray-world + highlight) is below
 * [MIN_USABLE_FRACTION] of the frame -- an image with almost no near-neutral or
 * near-white content to reason from -- estimation returns [WhiteBalanceGains.IDENTITY]
 * rather than guessing from noise.
 *
 * ## Gamma approximation
 *
 * Gains are applied directly to the 8-bit (gamma-encoded) channel values rather than in
 * linear light. This is an approximation: a physically exact white balance multiplies
 * *linear* radiance, so the correct pipeline would linearise, scale, then re-encode.
 * Applied on encoded values the correction is slightly non-linear in brightness, but for
 * the moderate gains this pass emits it tracks the true correction closely and avoids a
 * round-trip through a linearisation LUT. Full linearisation is a future refinement.
 *
 * Deterministic and free of Android dependencies (no randomness, no clock).
 */
object WhiteBalance {

    /**
     * Default per-channel gain bound. 1.6 covers up to a strong indoor (tungsten) cast;
     * the correction is clamped to `[1/1.6, 1.6]` so a wildly wrong estimate can never
     * push the image past a plausible correction.
     */
    const val DEFAULT_MAX_GAIN = 1.6

    /** Default correction strength (blend toward identity) when applied standalone. */
    const val DEFAULT_STRENGTH = 0.8

    /**
     * Gray-world saturation ceiling in [0, 1], `sat = (max - min) / max`. Chosen to
     * admit neutral surfaces that a strong cast has tinted (~0.36) and typical
     * pastel/skin tones while excluding vivid saturated colours (a pure hue sits ~0.8),
     * which must not enter the gray assumption.
     */
    const val GRAY_SATURATION_MAX = 0.6

    /** Gray-world luma floor (exclude near-black, where channel ratios are noise). */
    const val GRAY_LUMA_MIN = 0.12 * 255.0

    /** Gray-world luma ceiling (exclude near-clipped, where a channel has saturated). */
    const val GRAY_LUMA_MAX = 0.92 * 255.0

    /** Fraction trimmed from EACH tail of each channel's gray-world sample set. */
    const val GRAY_TRIM_FRACTION = 0.1

    /** Lower edge of the highlight-neutral luma percentile band. */
    const val HIGHLIGHT_PERCENTILE_LOW = 0.90

    /** Upper edge of the highlight-neutral luma percentile band. */
    const val HIGHLIGHT_PERCENTILE_HIGH = 0.99

    /** A channel at or above this is treated as clipped and excluded from the highlight cue. */
    const val HIGHLIGHT_CHANNEL_MAX = 250

    /**
     * Minimum usable sample count, as a fraction of the frame, for estimation to run;
     * below this the frame has too little near-neutral/near-white content to trust and
     * [estimateGains] returns identity.
     */
    const val MIN_USABLE_FRACTION = 0.01

    /**
     * Floor on each cue's combination weight. A cue is weighted by its sample count, but
     * never below this, so a small-but-valid cue keeps a baseline vote in the average.
     */
    const val CUE_WEIGHT_FLOOR = 32.0

    /**
     * Neutral tolerance (deadband) on the estimated per-channel gain deviation from 1.0.
     * A combined gain whose deviation from 1.0 is within this band is treated as scene
     * colour or estimator noise, NOT an illuminant cast, and is snapped to identity.
     *
     * This is the standard "don't chase a weak cast" guard: gray-world and even
     * highlight-neutral read a colourful-but-uncast scene (a scene whose surface
     * reflectances simply do not average to gray) as mildly cast, and correcting that
     * would desaturate genuine scene colour. The band is sized just above the residual a
     * neutral-but-colourful reference exhibits, so an already-balanced capture stays
     * untouched.
     */
    const val NEUTRAL_TOLERANCE = 0.09

    /**
     * Width of the confidence ramp above [NEUTRAL_TOLERANCE]. Between deviation
     * [NEUTRAL_TOLERANCE] and [NEUTRAL_TOLERANCE] + [NEUTRAL_SOFT_RANGE] the applied
     * fraction of the estimated deviation rises smoothly (a smoothstep) from 0 to 1; a
     * deviation beyond that is applied in full. This ramps in the correction rather than
     * stepping it on at the band edge, so a scene sitting near the boundary is corrected
     * gently and proportionally to how confidently it exceeds the neutral band, while a
     * genuine, strong cast is corrected at full estimated strength (no attenuation).
     */
    const val NEUTRAL_SOFT_RANGE = 0.11

    // Rec. 601 luma weights, matching [Luminance] / [ChromaDenoiser].
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114

    private const val EPS = 1e-6

    /**
     * One illuminant cue: the mean colour over its qualifying pixels and how many there
     * were. [count] == 0 means the cue found nothing usable.
     */
    internal data class Cue(
        val meanR: Double,
        val meanG: Double,
        val meanB: Double,
        val count: Int,
    )

    /**
     * Estimates bounded white-balance gains for [frame] (green-normalised, clamped to
     * `[1/maxGain, maxGain]`, identity if the frame is degenerate). This is the raw
     * estimate; [apply] additionally blends it toward identity by a strength.
     */
    fun estimateGains(
        frame: Frame,
        maxGain: Double = DEFAULT_MAX_GAIN,
        neutralTolerance: Double = NEUTRAL_TOLERANCE,
        neutralSoftRange: Double = NEUTRAL_SOFT_RANGE,
    ): WhiteBalanceGains {
        require(maxGain >= 1.0) { "maxGain must be >= 1.0" }
        require(neutralTolerance >= 0.0) { "neutralTolerance must be >= 0.0" }
        require(neutralSoftRange >= 0.0) { "neutralSoftRange must be >= 0.0" }
        val gray = grayWorldCue(frame)
        val highlight = highlightCue(frame)

        val n = frame.argb.size
        val minUsable = maxOf(1, (n * MIN_USABLE_FRACTION).roundToInt())
        if (gray.count + highlight.count < minUsable) return WhiteBalanceGains.IDENTITY

        val cues = listOf(gray, highlight).filter {
            it.count > 0 && it.meanR > EPS && it.meanG > EPS && it.meanB > EPS
        }
        if (cues.isEmpty()) return WhiteBalanceGains.IDENTITY

        var weightSum = 0.0
        var rGainAcc = 0.0
        var bGainAcc = 0.0
        for (c in cues) {
            val w = maxOf(c.count.toDouble(), CUE_WEIGHT_FLOOR)
            rGainAcc += w * (c.meanG / c.meanR)
            bGainAcc += w * (c.meanG / c.meanB)
            weightSum += w
        }
        val rGain = boundGain(rGainAcc / weightSum, maxGain, neutralTolerance, neutralSoftRange)
        val bGain = boundGain(bGainAcc / weightSum, maxGain, neutralTolerance, neutralSoftRange)
        return WhiteBalanceGains(rGain, 1.0, bGain)
    }

    /**
     * Applies the neutral-band confidence gate to a raw gain, then clamps to
     * `[1/maxGain, maxGain]`. A deviation from 1.0 within [neutralTolerance] snaps to
     * identity; over the [neutralSoftRange] above the tolerance the applied fraction
     * ramps smoothly (smoothstep) from 0 to 1; beyond that the full deviation is applied.
     */
    private fun boundGain(
        raw: Double,
        maxGain: Double,
        neutralTolerance: Double,
        neutralSoftRange: Double,
    ): Double {
        val deviation = raw - 1.0
        val magnitude = abs(deviation)
        if (magnitude <= neutralTolerance) return 1.0
        val fraction = if (neutralSoftRange <= 0.0) {
            1.0
        } else {
            val t = ((magnitude - neutralTolerance) / neutralSoftRange).coerceIn(0.0, 1.0)
            t * t * (3.0 - 2.0 * t) // smoothstep
        }
        val softened = 1.0 + deviation * fraction
        return softened.coerceIn(1.0 / maxGain, maxGain)
    }

    /**
     * White-balances [frame], preserving its dimensions and timestamp and forcing alpha
     * opaque. [strength] in [0, 1] blends the bounded gains toward identity (0 = off,
     * 1 = full). [maxGain] bounds the per-channel correction (see [estimateGains]).
     */
    fun apply(
        frame: Frame,
        strength: Double = DEFAULT_STRENGTH,
        maxGain: Double = DEFAULT_MAX_GAIN,
    ): Frame {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        val src = frame.argb
        if (strength == 0.0) {
            // No-op fast path, but still normalise alpha to opaque like the full path.
            return Frame(
                frame.width,
                frame.height,
                IntArray(src.size) { (0xFF shl 24) or (src[it] and 0x00FFFFFF) },
                frame.timestampMillis,
            )
        }

        val gains = estimateGains(frame, maxGain)
        val gR = 1.0 + strength * (gains.rGain - 1.0)
        val gG = 1.0 + strength * (gains.gGain - 1.0)
        val gB = 1.0 + strength * (gains.bGain - 1.0)

        // Per-pixel gain application: element-wise, so it is row-parallel. Estimation
        // (a whole-frame reduction) above stays serial.
        val out = IntArray(src.size)
        PipelineParallel.parallelRows(src.size) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)
                val nr = (r * gR).roundToInt().coerceIn(0, 255)
                val ng = (g * gG).roundToInt().coerceIn(0, 255)
                val nb = (b * gB).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    /**
     * The robust gray-world cue: trimmed per-channel means over pixels that are
     * near-neutral (saturation < [GRAY_SATURATION_MAX]) and mid-luma
     * ([GRAY_LUMA_MIN]..[GRAY_LUMA_MAX]). Exposed for direct testing of the estimator.
     */
    internal fun grayWorldCue(frame: Frame): Cue {
        val src = frame.argb
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        for (pixel in src) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
            if (luma < GRAY_LUMA_MIN || luma > GRAY_LUMA_MAX) continue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0.0 else (max - min).toDouble() / max
            if (sat > GRAY_SATURATION_MAX) continue
            rs.add(r); gs.add(g); bs.add(b)
        }
        if (rs.isEmpty()) return Cue(0.0, 0.0, 0.0, 0)
        return Cue(
            meanR = trimmedMean(rs, GRAY_TRIM_FRACTION),
            meanG = trimmedMean(gs, GRAY_TRIM_FRACTION),
            meanB = trimmedMean(bs, GRAY_TRIM_FRACTION),
            count = rs.size,
        )
    }

    /**
     * The highlight-neutral cue: mean colour over the brightest non-clipped luma band
     * ([HIGHLIGHT_PERCENTILE_LOW]..[HIGHLIGHT_PERCENTILE_HIGH] of the luma distribution,
     * every channel < [HIGHLIGHT_CHANNEL_MAX]). Exposed for direct testing.
     */
    internal fun highlightCue(frame: Frame): Cue {
        val src = frame.argb
        val n = src.size
        if (n == 0) return Cue(0.0, 0.0, 0.0, 0)
        val lumas = DoubleArray(n)
        for (i in 0 until n) {
            val pixel = src[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            lumas[i] = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        }
        val sorted = lumas.copyOf()
        sorted.sort()
        val lowThreshold = sorted[percentileIndex(n, HIGHLIGHT_PERCENTILE_LOW)]
        val highThreshold = sorted[percentileIndex(n, HIGHLIGHT_PERCENTILE_HIGH)]

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (i in 0 until n) {
            val luma = lumas[i]
            if (luma < lowThreshold || luma > highThreshold) continue
            val pixel = src[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r >= HIGHLIGHT_CHANNEL_MAX || g >= HIGHLIGHT_CHANNEL_MAX || b >= HIGHLIGHT_CHANNEL_MAX) continue
            sumR += r; sumG += g; sumB += b; count++
        }
        if (count == 0) return Cue(0.0, 0.0, 0.0, 0)
        return Cue(
            meanR = sumR.toDouble() / count,
            meanG = sumG.toDouble() / count,
            meanB = sumB.toDouble() / count,
            count = count,
        )
    }

    /** Index into a sorted array of [n] elements for percentile [p] in [0, 1]. */
    private fun percentileIndex(n: Int, p: Double): Int =
        (p * (n - 1)).roundToInt().coerceIn(0, n - 1)

    /**
     * Mean of [values] after dropping [trimFraction] of the count from each sorted tail.
     * With enough samples this discards outliers on both sides; if trimming would remove
     * everything it falls back to the untrimmed mean.
     */
    private fun trimmedMean(values: List<Int>, trimFraction: Double): Double {
        val sorted = values.sorted()
        val drop = (sorted.size * trimFraction).toInt()
        val from = drop
        val to = sorted.size - drop
        if (to <= from) return sorted.average()
        var sum = 0L
        for (i in from until to) sum += sorted[i]
        return sum.toDouble() / (to - from)
    }
}
