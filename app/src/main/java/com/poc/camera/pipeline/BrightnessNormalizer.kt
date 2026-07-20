package com.poc.camera.pipeline

import kotlin.math.roundToInt

/**
 * Per-frame brightness normalization for the night merge: matches a frame's overall
 * exposure to the reference before merging so auto-exposure drift across a long
 * hand-held burst does not bias the merge.
 *
 * The match statistic is a TRIMMED mean luma (a fraction of the darkest and brightest
 * pixels dropped), so a bright light source (a lamp) or crushed shadows cannot swing
 * the estimate the way a plain mean would. The recovered gain is clamped to a bounded
 * range so a mis-estimate on a degenerate frame can never blow out or crush it; the
 * bound also keeps the normalization honest -- it corrects AE fluctuation, not a genuine
 * exposure bracket.
 *
 * Pure math, no Android dependencies, deterministic.
 */
object BrightnessNormalizer {

    /**
     * Trimmed mean of the Rec. 601 luma of [frame], dropping the lowest and highest
     * [trimFraction] of pixels by luma. A [trimFraction] of 0 returns the plain mean.
     */
    fun trimmedMeanLuma(frame: Frame, trimFraction: Double): Double {
        val argb = frame.argb
        val n = argb.size
        if (n == 0) return 0.0
        val lumas = DoubleArray(n)
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            lumas[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        lumas.sort()
        val drop = (n * trimFraction).toInt().coerceIn(0, (n - 1) / 2)
        var sum = 0.0
        var count = 0
        for (i in drop until n - drop) {
            sum += lumas[i]
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /**
     * Bounded exposure gain that brings [frame]'s trimmed-mean luma onto [reference]'s:
     * reference / frame, clamped to [[minGain], [maxGain]]. Returns 1.0 when the frame's
     * trimmed mean is degenerate (<= 0), leaving it untouched.
     */
    fun matchGain(
        frame: Frame,
        reference: Frame,
        minGain: Double,
        maxGain: Double,
        trimFraction: Double,
    ): Double {
        val frameMean = trimmedMeanLuma(frame, trimFraction)
        if (frameMean <= 0.0) return 1.0
        val referenceMean = trimmedMeanLuma(reference, trimFraction)
        val gain = referenceMean / frameMean
        return gain.coerceIn(minGain, maxGain)
    }

    /** [frame] with every R, G and B scaled by [gain] and clamped to [0, 255]; alpha kept. */
    fun applyGain(frame: Frame, gain: Double): Frame {
        if (gain == 1.0) return frame
        val src = frame.argb
        val out = IntArray(src.size)
        for (i in src.indices) {
            val pixel = src[i]
            val r = ((((pixel shr 16) and 0xFF) * gain).roundToInt()).coerceIn(0, 255)
            val g = ((((pixel shr 8) and 0xFF) * gain).roundToInt()).coerceIn(0, 255)
            val b = (((pixel and 0xFF) * gain).roundToInt()).coerceIn(0, 255)
            out[i] = (pixel and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    /**
     * [frame] brightness-matched to [reference] under [params]: computes the bounded
     * [matchGain] and applies it. A no-op (returns [frame]) when the recovered gain is
     * exactly 1.0.
     */
    fun normalize(frame: Frame, reference: Frame, params: NightMergeParams): Frame {
        val gain = matchGain(frame, reference, params.minGain, params.maxGain, params.trimFraction)
        return applyGain(frame, gain)
    }
}
