package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BilinearUpsampler
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.SuperResolution
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures the multi-frame super-resolution result against the 2x ground-truth resolution
 * chart, quantifying how much detail beyond the single-frame input Nyquist SR recovers.
 *
 * The headline number is the aliasing-recovery factor: the amplitude of the
 * above-input-Nyquist bar frequency present in the SR output versus the same amplitude in a
 * plain bilinear upsample of the single reference frame. A bilinear upsample cannot invent
 * that frequency (the reference aliased it away), so a factor well above 1 is direct
 * evidence the burst resolved it. The near-Nyquist control band, resolvable by a single
 * frame, is reported alongside as a sanity check. Full-scene MAE against the 2x truth is
 * reported for SR and for a bilinear upsample of the merged output.
 *
 * Amplitude is a frequency-selective (matched) contrast: the band luma projected onto
 * cos/sin at the bar frequency, which isolates the bar detail from the smooth base. Pure
 * Kotlin, deterministic, no Android dependencies.
 */
object SuperResolutionQualityReport {

    /**
     * @property aboveSrAmplitude bar amplitude at the above-Nyquist frequency in the SR output.
     * @property aboveBilinearAmplitude the same in a bilinear upsample of the single reference.
     * @property aboveRecoveryFactor [aboveSrAmplitude] / [aboveBilinearAmplitude].
     * @property nearSrAmplitude bar amplitude of the near-Nyquist control band in the SR output.
     * @property nearBilinearAmplitude the same in the bilinear reference.
     * @property srMae full-scene MAE of the SR output vs the 2x truth.
     * @property bilinearMergedMae full-scene MAE of a bilinear upsample of the merged output vs truth.
     */
    data class Report(
        val aboveSrAmplitude: Double,
        val aboveBilinearAmplitude: Double,
        val aboveRecoveryFactor: Double,
        val nearSrAmplitude: Double,
        val nearBilinearAmplitude: Double,
        val srMae: Double,
        val bilinearMergedMae: Double,
    )

    fun measure(baseSeed: Long): Report {
        val truth = SyntheticScenes.resolutionChartTruth()
        val burst = SyntheticScenes.resolutionChartBurst(baseSeed)

        val result = SuperResolution.superResolve(burst)
        val sr = result.superResolved
        val bilinearReference = BilinearUpsampler.upsample2x(burst.first())
        val bilinearMerged = BilinearUpsampler.upsample2x(result.merged)

        val above = SyntheticScenes.srAboveNyquistBandBounds()
        val near = SyntheticScenes.srNearNyquistBandBounds()

        return Report(
            aboveSrAmplitude = barAmplitude(sr, above, SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD),
            aboveBilinearAmplitude = barAmplitude(bilinearReference, above, SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD),
            aboveRecoveryFactor = recoveryFactor(
                barAmplitude(sr, above, SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD),
                barAmplitude(bilinearReference, above, SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD),
            ),
            nearSrAmplitude = barAmplitude(sr, near, SyntheticScenes.SR_NEAR_NYQUIST_PERIOD),
            nearBilinearAmplitude = barAmplitude(bilinearReference, near, SyntheticScenes.SR_NEAR_NYQUIST_PERIOD),
            srMae = Mae.between(sr, truth),
            bilinearMergedMae = Mae.between(bilinearMerged, truth),
        )
    }

    private fun recoveryFactor(srAmplitude: Double, bilinearAmplitude: Double): Double =
        if (bilinearAmplitude <= 1e-9) Double.POSITIVE_INFINITY else srAmplitude / bilinearAmplitude

    /**
     * Amplitude of the [period]-pixel horizontal (vertical-bar) frequency component over the
     * window [bounds] = (x0, y0, x1, y1) of [frame], via a matched cos/sin projection of
     * luma. For a signal A*cos(2*pi*x/period) the projection returns ~A, isolating the bar
     * contrast from the smooth base.
     */
    fun barAmplitude(frame: Frame, bounds: IntArray, period: Double): Double {
        val (x0, y0, x1, y1) = bounds
        val width = frame.width
        val argb = frame.argb
        var sumCos = 0.0
        var sumSin = 0.0
        var count = 0
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
                val p = argb[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val phase = 2.0 * PI * x / period
                sumCos += luma * cos(phase)
                sumSin += luma * sin(phase)
                count++
            }
        }
        if (count == 0) return 0.0
        return 2.0 * sqrt(sumCos * sumCos + sumSin * sumSin) / count
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]
}
