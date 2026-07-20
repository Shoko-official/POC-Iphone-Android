package com.poc.camera.pipeline

import kotlin.math.pow

/**
 * Smooth global tone mapping applied per RGB channel through a precomputed
 * 256-entry lookup table (built once per parameter set, then reused for every
 * pixel).
 *
 * The curve is a filmic-style S-shape driven by two knobs in [0, 1]:
 * [shadowsLift] brightens dark values via a gamma < 1, and [highlightRolloff]
 * softens the upper range with a shoulder bump. Both endpoints (0 and 255) map
 * to themselves, the table is monotonically non-decreasing, and neutral
 * parameters (0, 0) reproduce the identity within rounding. Pure math with no
 * Android dependencies, so it is exercised directly in JVM unit tests.
 */
class ToneCurve(shadowsLift: Double, highlightRolloff: Double) {

    /** Precomputed [0,255] -> [0,255] mapping shared by all three channels. */
    val lookupTable: IntArray = buildLookupTable(shadowsLift, highlightRolloff)

    /** Maps every RGB channel of [frame] through the table, preserving alpha. */
    fun apply(frame: Frame): Frame {
        val src = frame.argb
        val out = IntArray(src.size)
        val lut = lookupTable
        // Per-pixel LUT map is element-wise (row-parallel); the 256-entry LUT itself is
        // built once, serially, in the constructor.
        PipelineParallel.parallelRows(src.size) { start, end ->
            for (i in start until end) {
                val pixel = src[i]
                val a = (pixel ushr 24) and 0xFF
                val r = lut[(pixel shr 16) and 0xFF]
                val g = lut[(pixel shr 8) and 0xFF]
                val b = lut[pixel and 0xFF]
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(frame.width, frame.height, out, frame.timestampMillis)
    }

    companion object {
        // Peak displacement of each knob at full strength. Both stay well inside
        // the range that keeps the composed curve monotonic (verified in tests).
        private const val SHADOW_STRENGTH = 0.5
        private const val HIGHLIGHT_STRENGTH = 0.25

        // Maximum of the raw shoulder shape x^3*(1-x), reached at x = 0.75. Used
        // to normalise the bump so HIGHLIGHT_STRENGTH is its peak displacement.
        private const val HIGHLIGHT_PEAK = 0.75 * 0.75 * 0.75 * 0.25

        private fun buildLookupTable(shadowsLift: Double, highlightRolloff: Double): IntArray {
            val lift = shadowsLift.coerceIn(0.0, 1.0)
            val rolloff = highlightRolloff.coerceIn(0.0, 1.0)
            val gamma = 1.0 / (1.0 + SHADOW_STRENGTH * lift)

            val lut = IntArray(256)
            for (i in 0..255) {
                val x = i / 255.0
                // Shadow lift: gamma < 1 raises darks while fixing 0 and 1.
                val lifted = x.pow(gamma)
                // Highlight rolloff: subtract a shoulder bump that vanishes at
                // both endpoints and peaks in the highlights.
                val bump = (lifted * lifted * lifted * (1.0 - lifted)) / HIGHLIGHT_PEAK
                val y = lifted - HIGHLIGHT_STRENGTH * rolloff * bump
                lut[i] = (y * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
            return lut
        }
    }
}
