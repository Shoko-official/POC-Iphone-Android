package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.SyntheticScenes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalence gate for the histogram-based white-balance estimator internals (issue #117).
 *
 * The gray-world cue's boxed per-pixel sample lists + sort-based trimmed means were
 * replaced by 256-bin per-channel histograms, and the highlight cue's whole-frame
 * double-luma copy + sort by an exact integer milli-luma histogram percentile. This test
 * replicates the PREVIOUS implementation verbatim as an in-test reference and measures the
 * new estimator against it across the synthetic scene family:
 *
 *  - Gray-world cue: EXACT (bit-identical means, asserted at delta 0.0). A 256-bin
 *    histogram is a lossless encoding of an 8-bit sample multiset, so the histogram
 *    trimmed mean drops the same tail counts, sums the same integers and divides by the
 *    same divisor as the sorted-list version.
 *  - Highlight cue: near-exact. The integer milli-luma ordering IS the exact mathematical
 *    luma ordering; the double-sorted reference can disagree with it only where
 *    floating-point rounding at the exact percentile band edge flips a comparison, moving
 *    at most a few edge-tied pixels in or out of the band. The measured mean drift is
 *    gated at a twentieth of a code and printed for the record.
 *  - Combined gains: gated at a 0.01% relative delta and printed.
 */
class WhiteBalanceHistogramEquivalenceTest {

    @Test
    fun grayWorldCueIsBitIdenticalToTheSampleListReference() {
        for ((name, frame) in scenes()) {
            val ref = referenceGrayWorldCue(frame)
            val cue = WhiteBalance.grayWorldCue(frame)
            assertEquals("$name gray count", ref.count, cue.count)
            assertEquals("$name gray meanR", ref.meanR, cue.meanR, 0.0)
            assertEquals("$name gray meanG", ref.meanG, cue.meanG, 0.0)
            assertEquals("$name gray meanB", ref.meanB, cue.meanB, 0.0)
        }
    }

    @Test
    fun highlightCueTracksTheSortBasedReferenceWithinAFractionOfACode() {
        for ((name, frame) in scenes()) {
            val ref = referenceHighlightCue(frame)
            val cue = WhiteBalance.highlightCue(frame)
            val dR = abs(ref.meanR - cue.meanR)
            val dG = abs(ref.meanG - cue.meanG)
            val dB = abs(ref.meanB - cue.meanB)
            println(
                "highlight %-18s ref count=%d mean=(%.4f %.4f %.4f) new count=%d mean=(%.4f %.4f %.4f)".format(
                    name, ref.count, ref.meanR, ref.meanG, ref.meanB,
                    cue.count, cue.meanR, cue.meanG, cue.meanB,
                ),
            )
            // Band-edge floating-point ties can move a handful of pixels between the two
            // implementations; the cue means must stay within a twentieth of a code.
            assertTrue("$name highlight count drifted: ${ref.count} vs ${cue.count}", abs(ref.count - cue.count) <= maxOf(2, ref.count / 100))
            assertTrue("$name highlight meanR drift $dR exceeds 0.05", dR <= 0.05)
            assertTrue("$name highlight meanG drift $dG exceeds 0.05", dG <= 0.05)
            assertTrue("$name highlight meanB drift $dB exceeds 0.05", dB <= 0.05)
        }
    }

    @Test
    fun estimatedGainsMatchThePreviousImplementation() {
        for ((name, frame) in scenes()) {
            val ref = referenceEstimateGains(frame)
            val gains = WhiteBalance.estimateGains(frame)
            val rDelta = relDelta(ref.rGain, gains.rGain)
            val bDelta = relDelta(ref.bGain, gains.bGain)
            println(
                "gains %-18s ref=(r=%.6f b=%.6f) new=(r=%.6f b=%.6f) rDelta=%.6f%% bDelta=%.6f%%".format(
                    name, ref.rGain, ref.bGain, gains.rGain, gains.bGain, rDelta * 100, bDelta * 100,
                ),
            )
            assertTrue("$name rGain delta ${rDelta * 100}% exceeds 0.01%", rDelta <= 1e-4)
            assertTrue("$name bGain delta ${bDelta * 100}% exceeds 0.01%", bDelta <= 1e-4)
        }
    }

    // --- the previous estimator, replicated verbatim as the reference -------------------

    private data class RefCue(val meanR: Double, val meanG: Double, val meanB: Double, val count: Int)

    private fun referenceGrayWorldCue(frame: Frame): RefCue {
        val src = frame.argb
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        for (pixel in src) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
            if (luma < WhiteBalance.GRAY_LUMA_MIN || luma > WhiteBalance.GRAY_LUMA_MAX) continue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0.0 else (max - min).toDouble() / max
            if (sat > WhiteBalance.GRAY_SATURATION_MAX) continue
            rs.add(r); gs.add(g); bs.add(b)
        }
        if (rs.isEmpty()) return RefCue(0.0, 0.0, 0.0, 0)
        return RefCue(
            meanR = referenceTrimmedMean(rs, WhiteBalance.GRAY_TRIM_FRACTION),
            meanG = referenceTrimmedMean(gs, WhiteBalance.GRAY_TRIM_FRACTION),
            meanB = referenceTrimmedMean(bs, WhiteBalance.GRAY_TRIM_FRACTION),
            count = rs.size,
        )
    }

    private fun referenceTrimmedMean(values: List<Int>, trimFraction: Double): Double {
        val sorted = values.sorted()
        val drop = (sorted.size * trimFraction).toInt()
        val from = drop
        val to = sorted.size - drop
        if (to <= from) return sorted.average()
        var sum = 0L
        for (i in from until to) sum += sorted[i]
        return sum.toDouble() / (to - from)
    }

    private fun referenceHighlightCue(frame: Frame): RefCue {
        val src = frame.argb
        val n = src.size
        if (n == 0) return RefCue(0.0, 0.0, 0.0, 0)
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
        val lowThreshold = sorted[percentileIndex(n, WhiteBalance.HIGHLIGHT_PERCENTILE_LOW)]
        val highThreshold = sorted[percentileIndex(n, WhiteBalance.HIGHLIGHT_PERCENTILE_HIGH)]

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
            if (r >= WhiteBalance.HIGHLIGHT_CHANNEL_MAX || g >= WhiteBalance.HIGHLIGHT_CHANNEL_MAX || b >= WhiteBalance.HIGHLIGHT_CHANNEL_MAX) continue
            sumR += r; sumG += g; sumB += b; count++
        }
        if (count == 0) return RefCue(0.0, 0.0, 0.0, 0)
        return RefCue(sumR.toDouble() / count, sumG.toDouble() / count, sumB.toDouble() / count, count)
    }

    private fun percentileIndex(n: Int, p: Double): Int =
        (p * (n - 1)).roundToInt().coerceIn(0, n - 1)

    private fun referenceEstimateGains(frame: Frame, maxGain: Double = WhiteBalance.DEFAULT_MAX_GAIN): WhiteBalanceGains {
        val gray = referenceGrayWorldCue(frame)
        val highlight = referenceHighlightCue(frame)

        val n = frame.argb.size
        val minUsable = maxOf(1, (n * WhiteBalance.MIN_USABLE_FRACTION).roundToInt())
        if (gray.count + highlight.count < minUsable) return WhiteBalanceGains.IDENTITY

        val cues = listOf(gray, highlight).filter {
            it.count > 0 && it.meanR > EPS && it.meanG > EPS && it.meanB > EPS
        }
        if (cues.isEmpty()) return WhiteBalanceGains.IDENTITY

        var weightSum = 0.0
        var rGainAcc = 0.0
        var bGainAcc = 0.0
        for (c in cues) {
            val w = maxOf(c.count.toDouble(), WhiteBalance.CUE_WEIGHT_FLOOR)
            rGainAcc += w * (c.meanG / c.meanR)
            bGainAcc += w * (c.meanG / c.meanB)
            weightSum += w
        }
        var rawR = rGainAcc / weightSum
        var rawB = bGainAcc / weightSum

        // Mirrors the issue #175 anti-overshoot probe cap: the probe cue is plain direct
        // sums (no histogram involved), so the reference reuses the production cue and
        // replicates only the combination arithmetic under equivalence test.
        val probe = WhiteBalance.neutralProbeCue(frame)
        if (
            probe.count >= maxOf(1, (n * WhiteBalance.NEUTRAL_PROBE_MIN_FRACTION).roundToInt()) &&
            probe.meanR > EPS && probe.meanG > EPS && probe.meanB > EPS
        ) {
            rawR = referenceCapTowardProbe(rawR, probe.meanG / probe.meanR)
            rawB = referenceCapTowardProbe(rawB, probe.meanG / probe.meanB)
        }

        val rGain = referenceBoundGain(rawR, maxGain)
        val bGain = referenceBoundGain(rawB, maxGain)
        return WhiteBalanceGains(rGain, 1.0, bGain)
    }

    private fun referenceCapTowardProbe(raw: Double, probeGain: Double): Double {
        val upper = maxOf(1.0, probeGain * (1.0 + WhiteBalance.NEUTRAL_PROBE_TOLERANCE))
        val lower = minOf(1.0, probeGain * (1.0 - WhiteBalance.NEUTRAL_PROBE_TOLERANCE))
        return raw.coerceIn(lower, upper)
    }

    private fun referenceBoundGain(raw: Double, maxGain: Double): Double {
        val deviation = raw - 1.0
        val magnitude = abs(deviation)
        if (magnitude <= WhiteBalance.NEUTRAL_TOLERANCE) return 1.0
        val fraction = if (WhiteBalance.NEUTRAL_SOFT_RANGE <= 0.0) {
            1.0
        } else {
            val t = ((magnitude - WhiteBalance.NEUTRAL_TOLERANCE) / WhiteBalance.NEUTRAL_SOFT_RANGE).coerceIn(0.0, 1.0)
            t * t * (3.0 - 2.0 * t)
        }
        val softened = 1.0 + deviation * fraction
        return softened.coerceIn(1.0 / maxGain, maxGain)
    }

    private fun relDelta(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        return abs(a - b) / abs(a).coerceAtLeast(1e-9)
    }

    // --- deterministic content ----------------------------------------------------------

    /** The scene family: smooth colour, casts, noise, outliers, highlight bands, solids. */
    private fun scenes(): List<Pair<String, Frame>> {
        val colorful = colorfulScene(600, 600, 0xE7L)
        return listOf(
            "colorful" to colorful,
            "warm-cast" to withCast(colorful, rGain = 1.30, bGain = 0.75),
            "cool-cast" to withCast(colorful, rGain = 0.85, bGain = 1.20),
            "noisy-warm-cast" to SyntheticScenes.noisy(withCast(colorful, rGain = 1.25, bGain = 0.80), 0x51L),
            "gray-with-outliers" to grayFieldWithOutliers(64, 150, 120, 96, outlierEvery = 17),
            "highlight-band" to highlightBandScene(64),
            "solid-strong-cast" to solid(48, 48, 180, 120, 85),
            "solid-mild-cast" to solid(48, 48, 132, 128, 124),
        )
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun solid(width: Int, height: Int, r: Int, g: Int, b: Int): Frame =
        Frame(width, height, IntArray(width * height) { argb(r, g, b) }, 0L)

    private fun withCast(frame: Frame, rGain: Double, bGain: Double): Frame {
        val src = frame.argb
        val out = IntArray(src.size) { i ->
            val p = src[i]
            argb(
                (((p shr 16) and 0xFF) * rGain).toInt(),
                (p shr 8) and 0xFF,
                ((p and 0xFF) * bGain).toInt(),
            )
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    /** Mirrors the WhiteBalanceTest gray field: jittered neutral with vivid red outliers. */
    private fun grayFieldWithOutliers(size: Int, neutralR: Int, neutralG: Int, neutralB: Int, outlierEvery: Int): Frame {
        var state = 0x1234_5678L
        fun jitter(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 20) % 7L).toInt() - 3
        }
        val out = IntArray(size * size) { i ->
            if (i % outlierEvery == 0) {
                argb(250, 5, 5)
            } else {
                argb(neutralR + jitter(), neutralG + jitter(), neutralB + jitter())
            }
        }
        return Frame(size, size, out, 0L)
    }

    /** Dark saturated body with a bright warm near-white band (the highlight probe). */
    private fun highlightBandScene(size: Int): Frame {
        val out = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[y * size + x] = if (y < size - 8) argb(120, 20, 20) else argb(235, 220, 190)
            }
        }
        return Frame(size, size, out, 0L)
    }

    private fun colorfulScene(width: Int, height: Int, seed: Long): Frame {
        val r = lattice(width, height, seed xor 0x111111L, cell = 40)
        val g = lattice(width, height, seed xor 0x222222L, cell = 52)
        val b = lattice(width, height, seed xor 0x333333L, cell = 34)
        val out = IntArray(width * height) { i -> argb(r[i], g[i], b[i]) }
        return Frame(width, height, out, timestampMillis = 3L)
    }

    private fun lattice(width: Int, height: Int, seed: Long, cell: Int): IntArray {
        val lcg = SyntheticImages.Lcg(seed)
        val gridWidth = width / cell + 2
        val gridHeight = height / cell + 2
        val grid = IntArray(gridWidth * gridHeight) { lcg.nextByte() }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = y / cell
            val fy = (y % cell).toDouble() / cell
            for (x in 0 until width) {
                val gx = x / cell
                val fx = (x % cell).toDouble() / cell
                val v00 = grid[gy * gridWidth + gx]
                val v10 = grid[gy * gridWidth + gx + 1]
                val v01 = grid[(gy + 1) * gridWidth + gx]
                val v11 = grid[(gy + 1) * gridWidth + gx + 1]
                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[y * width + x] = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private companion object {
        // Rec. 601 weights, matching the private constants inside WhiteBalance.
        const val R_WEIGHT = 0.299
        const val G_WEIGHT = 0.587
        const val B_WEIGHT = 0.114
        const val EPS = 1e-6
    }
}
