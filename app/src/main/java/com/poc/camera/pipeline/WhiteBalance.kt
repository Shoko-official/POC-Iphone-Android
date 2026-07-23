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
 * The per-channel means over the qualifying pixels are **trimmed means**: each channel
 * drops [grayTrimFraction] of its qualifying sample count from each sorted tail before
 * averaging, so a residual cluster of coloured outliers that slipped under the
 * saturation gate cannot drag the estimate. The samples are 8-bit, so each channel's
 * sorted sequence is held losslessly in a 256-bin histogram and the trimmed mean is read
 * from it exactly (issue #117) -- no boxed per-pixel sample lists, no sort.
 *
 * **2. Highlight-neutral.** Bright near-white surfaces are the best illuminant probes:
 * a specular highlight or a white wall reflects the illuminant almost directly, so its
 * captured colour is the illuminant colour with little surface tint. This cue averages
 * the pixels whose luma falls in the brightest non-clipped percentile band
 * ([highlightPercentileLow]..[highlightPercentileHigh]) with every channel strictly
 * below [highlightChannelMax] (a clipped channel carries no colour information). The
 * percentile band is selected by rank from an exact integer luma histogram (Rec. 601
 * milli-weights 299/587/114, one bin per representable value -- see [milliLuma]) instead
 * of the previous whole-frame double-luma copy + sort (issue #117); the integer ordering
 * IS the exact mathematical luma ordering, so the band can differ from the double-sorted
 * one only where floating-point rounding at the exact band edge disagreed with the true
 * ordering (measured at identical gains on every equivalence scene -- see
 * WhiteBalanceHistogramEquivalenceTest).
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
     * Saturation ceiling of the strict-neutral PROBE population (issue #175). Far below
     * [GRAY_SATURATION_MAX]: a pixel this close to gray is either a genuinely neutral
     * surface under a balanced illuminant or a neutral surface under at most a very mild
     * cast - either way it is the scene's own most reliable illuminant probe.
     */
    const val NEUTRAL_PROBE_SATURATION_MAX = 0.10

    /**
     * Minimum fraction of the frame the strict-neutral probe must cover to be trusted.
     *
     * Sized from measured probe fractions across the synthetic scene family: genuinely
     * cast scenes still contain a thin slice of ACCIDENTAL near-neutrals (hues the cast
     * happens to compensate, plus near-clipped whites where the cast compresses away) -
     * measured at 2.8-5.5% on the cast colorchart scenes - while scenes with real neutral
     * content measure 10%+ (colorchart 16%, skin chart background 19%, gray-dominant
     * scenes 38-96%). 0.08 sits between the two populations with margin on both sides, so
     * the cap never engages from accidental neutrals under a genuine cast, and a
     * real gray surface (card, wall, pavement) covering under ~8% of the frame simply
     * does not arm the guard.
     */
    const val NEUTRAL_PROBE_MIN_FRACTION = 0.08

    /** Tolerance widening the probe-derived gain interval (see [capTowardProbe]). */
    const val NEUTRAL_PROBE_TOLERANCE = 0.005

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

    // The same Rec. 601 weights in exact integer milli-form (x1000, summing to 1000), so
    // [milliLuma] is an integer in [0, 255000] whose ordering is the exact mathematical
    // luma ordering -- the basis of the histogram percentile in [highlightCue].
    private const val R_WEIGHT_MILLI = 299
    private const val G_WEIGHT_MILLI = 587
    private const val B_WEIGHT_MILLI = 114
    private const val MILLI_LUMA_BINS = 255 * 1000 + 1

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
        var rawR = rGainAcc / weightSum
        var rawB = bGainAcc / weightSum

        // Anti-overshoot guard (issue #175). The saturation admission gate alone cannot
        // separate a warm SURFACE from a warm ILLUMINANT: a frame dominated by
        // low-saturation wood reads as a tungsten cast, and the correction pushes the
        // scene's TRUE neutrals past neutral into blue. When a meaningful strictly-neutral
        // population exists it is the scene's own illuminant probe, and the correction is
        // capped so it can never push that probe PAST neutral. Under a genuine cast the
        // probe population either does not exist (everything is tinted beyond the strict
        // gate - the cap never engages) or is itself tinted (its own neutralising gains
        // are far from 1, so the interval is wide and the estimated correction passes
        // through untouched, up to the probe's own level).
        val probe = neutralProbeCue(frame)
        if (
            probe.count >= maxOf(1, (n * NEUTRAL_PROBE_MIN_FRACTION).roundToInt()) &&
            probe.meanR > EPS && probe.meanG > EPS && probe.meanB > EPS
        ) {
            rawR = capTowardProbe(rawR, probe.meanG / probe.meanR, NEUTRAL_PROBE_TOLERANCE)
            rawB = capTowardProbe(rawB, probe.meanG / probe.meanB, NEUTRAL_PROBE_TOLERANCE)
        }

        val rGain = boundGain(rawR, maxGain, neutralTolerance, neutralSoftRange)
        val bGain = boundGain(rawB, maxGain, neutralTolerance, neutralSoftRange)
        return WhiteBalanceGains(rGain, 1.0, bGain)
    }

    /**
     * Constrains a raw gain to the interval spanned by identity and [probeGain] (the gain
     * that would exactly neutralise the strict-neutral probe), widened by [tolerance] on
     * the probe side. A correction may go as far as the probe justifies - never past it,
     * and never in a direction that moves an already-neutral probe away from neutral.
     */
    private fun capTowardProbe(raw: Double, probeGain: Double, tolerance: Double): Double {
        val upper = maxOf(1.0, probeGain * (1.0 + tolerance))
        val lower = minOf(1.0, probeGain * (1.0 - tolerance))
        return raw.coerceIn(lower, upper)
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

        return applyGains(frame, estimateGains(frame, maxGain), strength)
    }

    /**
     * Applies a set of PRE-COMPUTED [gains] to [frame], blending them toward identity by
     * [strength] (`g' = 1 + strength * (g - 1)`), preserving dimensions and timestamp and
     * forcing alpha opaque. This is the per-pixel half of [apply] with the whole-frame
     * estimation reduction hoisted out, so a caller that already has the gains (e.g. the
     * tiled finishing path, which estimates ONCE on the whole frame and then applies the
     * same gains tile-by-tile) can reuse them instead of re-estimating per region. Passing
     * `estimateGains(frame)` reproduces [apply] exactly.
     */
    fun applyGains(
        frame: Frame,
        gains: WhiteBalanceGains,
        strength: Double = DEFAULT_STRENGTH,
    ): Frame {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        val src = frame.argb
        val gR = 1.0 + strength * (gains.rGain - 1.0)
        val gG = 1.0 + strength * (gains.gGain - 1.0)
        val gB = 1.0 + strength * (gains.bGain - 1.0)

        // Per-pixel gain application: element-wise, so it is row-parallel. Estimation
        // (a whole-frame reduction) stays with the caller.
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
     * ([GRAY_LUMA_MIN]..[GRAY_LUMA_MAX]). The qualifying samples are accumulated into
     * 256-bin per-channel histograms -- a lossless encoding of each 8-bit sample multiset,
     * so [trimmedMean] reads the exact sorted-and-trimmed mean the previous boxed sample
     * lists produced, at a fixed 3 KB (issue #117). The luma/saturation gates themselves
     * are unchanged. Exposed for direct testing of the estimator.
     */
    internal fun grayWorldCue(frame: Frame): Cue {
        val src = frame.argb
        val rHist = IntArray(256)
        val gHist = IntArray(256)
        val bHist = IntArray(256)
        var count = 0
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
            rHist[r]++; gHist[g]++; bHist[b]++; count++
        }
        if (count == 0) return Cue(0.0, 0.0, 0.0, 0)
        return Cue(
            meanR = trimmedMean(rHist, count, GRAY_TRIM_FRACTION),
            meanG = trimmedMean(gHist, count, GRAY_TRIM_FRACTION),
            meanB = trimmedMean(bHist, count, GRAY_TRIM_FRACTION),
            count = count,
        )
    }

    /**
     * The strict-neutral probe cue (issue #175): mean colour over mid-luma pixels whose
     * saturation is below [NEUTRAL_PROBE_SATURATION_MAX] - a far tighter gate than the
     * gray-world admission. Used only by the anti-overshoot cap in [estimateGains], never
     * as a voting cue. Plain means (no trimming): the population is already the most
     * selective one the estimator has. Exposed for direct testing.
     */
    internal fun neutralProbeCue(frame: Frame): Cue {
        val src = frame.argb
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (pixel in src) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
            if (luma < GRAY_LUMA_MIN || luma > GRAY_LUMA_MAX) continue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0.0 else (max - min).toDouble() / max
            if (sat > NEUTRAL_PROBE_SATURATION_MAX) continue
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

    /**
     * The highlight-neutral cue: mean colour over the brightest non-clipped luma band
     * ([HIGHLIGHT_PERCENTILE_LOW]..[HIGHLIGHT_PERCENTILE_HIGH] of the luma distribution,
     * every channel < [HIGHLIGHT_CHANNEL_MAX]). The band edges are the same percentile
     * ranks as before, but selected from an exact integer [milliLuma] histogram instead
     * of a whole-frame double-luma copy + sort (issue #117; see the class doc for the
     * bounded difference this can make at the exact band edge). Exposed for direct
     * testing.
     */
    internal fun highlightCue(frame: Frame): Cue {
        val src = frame.argb
        val n = src.size
        if (n == 0) return Cue(0.0, 0.0, 0.0, 0)
        val hist = IntArray(MILLI_LUMA_BINS)
        for (pixel in src) {
            hist[milliLuma(pixel)]++
        }
        val lowThreshold = rankValue(hist, percentileIndex(n, HIGHLIGHT_PERCENTILE_LOW))
        val highThreshold = rankValue(hist, percentileIndex(n, HIGHLIGHT_PERCENTILE_HIGH))

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        for (pixel in src) {
            val luma = milliLuma(pixel)
            if (luma < lowThreshold || luma > highThreshold) continue
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

    /**
     * Exact integer Rec. 601 luma of an ARGB pixel in milli-units (`299r + 587g + 114b`,
     * in [0, 255000]). Adjacent representable lumas differ by 1/1000 of a code while the
     * double form's rounding error is ~1e-13 of a code, so this ordering is the exact
     * mathematical luma ordering the double form approximates.
     */
    private fun milliLuma(pixel: Int): Int =
        R_WEIGHT_MILLI * ((pixel shr 16) and 0xFF) +
            G_WEIGHT_MILLI * ((pixel shr 8) and 0xFF) +
            B_WEIGHT_MILLI * (pixel and 0xFF)

    /** Index into a sorted array of [n] elements for percentile [p] in [0, 1]. */
    private fun percentileIndex(n: Int, p: Double): Int =
        (p * (n - 1)).roundToInt().coerceIn(0, n - 1)

    /** The [rank]-th smallest (0-based) sample recorded in [hist]. */
    private fun rankValue(hist: IntArray, rank: Int): Int {
        var seen = 0
        for (value in hist.indices) {
            seen += hist[value]
            if (seen > rank) return value
        }
        return hist.size - 1
    }

    /**
     * Mean of the [count] samples in the 256-bin [hist] after dropping [trimFraction] of
     * the count from each sorted tail. Walking the histogram in bin order visits exactly
     * the sorted 8-bit sample sequence, so this equals the previous sort-then-trim mean
     * bit for bit (the tail drop count, the trimmed integer sum and the divisor are all
     * identical). If trimming would remove everything it falls back to the untrimmed
     * mean, as before.
     */
    private fun trimmedMean(hist: IntArray, count: Int, trimFraction: Double): Double {
        val drop = (count * trimFraction).toInt()
        var from = drop
        var to = count - drop
        if (to <= from) {
            from = 0
            to = count
        }
        var sum = 0L
        var seen = 0
        for (value in hist.indices) {
            val binCount = hist[value]
            if (binCount == 0) continue
            val binFrom = maxOf(seen, from)
            val binTo = minOf(seen + binCount, to)
            if (binTo > binFrom) sum += value.toLong() * (binTo - binFrom)
            seen += binCount
            if (seen >= to) break
        }
        return sum.toDouble() / (to - from)
    }
}
