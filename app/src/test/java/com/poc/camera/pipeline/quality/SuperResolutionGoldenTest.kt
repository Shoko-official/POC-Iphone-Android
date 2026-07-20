package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BilinearUpsampler
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.SuperResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden proof for multi-frame super-resolution: it resolves detail beyond the
 * single-frame input Nyquist on a synthetic resolution chart, gates ghosts, and is
 * deterministic. The chart, its bands and the phase-diverse burst live in
 * [SyntheticScenes]; the measurements in [SuperResolutionQualityReport].
 *
 * The numbers below are MEASURED baselines of the current implementation (captured
 * 2026-07-20) with generous tolerance -- a regression tripwire, not a tight SLA. Do not
 * loosen them without an explicit, justified reason.
 *
 * Measured at SEED:
 *   above-Nyquist bar amplitude   SR 21.98  vs bilinear reference 4.64  -> factor 4.73x
 *   near-Nyquist control amplitude SR 22.18 vs bilinear reference 19.97 (both resolve it)
 *   full-scene MAE vs 2x truth     SR 4.87  vs bilinear-of-merged 10.03
 *   ghost moved-region mean        SR 94.96 vs reference upsample 94.75 (block 235 excluded)
 */
class SuperResolutionGoldenTest {

    @Test
    fun resolvesDetailBeyondInputNyquist() {
        val report = SuperResolutionQualityReport.measure(SEED)

        // THE aliasing-recovery proof: the above-input-Nyquist bar frequency is present in
        // the SR output at many times the amplitude a bilinear upsample of the single
        // reference can show -- a bilinear upsample cannot invent it, so the burst resolved it.
        assertTrue(
            "SR above-Nyquist amplitude ${report.aboveSrAmplitude} should be strong",
            report.aboveSrAmplitude > 15.0,
        )
        assertTrue(
            "SR/bilinear above-Nyquist recovery factor ${report.aboveRecoveryFactor} should exceed 3x " +
                "(SR ${report.aboveSrAmplitude} vs bilinear ${report.aboveBilinearAmplitude})",
            report.aboveRecoveryFactor > 3.0,
        )

        // The near-Nyquist control band is resolvable by a single frame, so both show it;
        // SR must not be softer than bilinear there.
        assertTrue(
            "SR near-Nyquist amplitude ${report.nearSrAmplitude} should be >= bilinear " +
                "${report.nearBilinearAmplitude} (allowing a small margin)",
            report.nearSrAmplitude > report.nearBilinearAmplitude * 0.9,
        )
    }

    @Test
    fun fullSceneBeatsBilinearUpsampleOfMerge() {
        val report = SuperResolutionQualityReport.measure(SEED)
        assertTrue(
            "SR full-scene MAE ${report.srMae} should beat bilinear-of-merged ${report.bilinearMergedMae}",
            report.srMae < report.bilinearMergedMae,
        )
        assertTrue("SR full-scene MAE ${report.srMae} regressed above ceiling", report.srMae < 6.0)
    }

    @Test
    fun ghostGatingKeepsMovedObjectOutOfMovedRegion() {
        val burst = SyntheticScenes.resolutionChartMovedObjectBurst(GHOST_SEED)
        val result = SuperResolution.superResolve(burst)
        val sr = result.superResolved
        val referenceUpsample = BilinearUpsampler.upsample2x(burst.first())

        // The block sits at "home" in the reference and jumps to the "moved" region in every
        // other frame. If SR double-exposed it, the moved region would brighten toward the
        // block value (235). It must instead stay at the background the reference shows.
        val moved = SyntheticScenes.srGhostBlockMovedBounds()
        val srMean = regionLumaMean(sr, moved[0] * 2, moved[1] * 2, moved[2] * 2, moved[3] * 2)
        val refMean = regionLumaMean(referenceUpsample, moved[0] * 2, moved[1] * 2, moved[2] * 2, moved[3] * 2)

        assertTrue(
            "SR moved-region mean $srMean should match the reference background $refMean, not the block (235)",
            abs(srMean - refMean) < 8.0,
        )
        assertTrue("SR moved-region mean $srMean must stay far below the block value 235", srMean < 150.0)
    }

    @Test
    fun isDeterministic() {
        val first = SuperResolution.superResolve(SyntheticScenes.resolutionChartBurst(SEED))
        val second = SuperResolution.superResolve(SyntheticScenes.resolutionChartBurst(SEED))
        assertEquals(first.superResolved, second.superResolved)
        assertEquals(first.merged, second.merged)
        assertEquals(first.usedFrameCount, second.usedFrameCount)
    }

    @Test
    fun lowCoverageBordersFallBackToBilinearUpsampleOfMerge() {
        // Shift every non-reference frame right by 8 px: their pixels then reach only
        // reference columns up to width-9, so the far-right output columns get no burst
        // sample at all. Odd columns there (the reference lands only on even columns) have
        // zero coverage and must equal the fallback layer -- a bilinear upsample of the merge.
        val clean = SyntheticScenes.clean("texture")
        val shifted = SyntheticScenes.noisyShifted(clean, seed = 7L, dx = 8, dy = 0, readNoise = 0.0, shotGain = 0.0)
        val burst = listOf(clean, shifted, shifted, shifted)

        val result = SuperResolution.superResolve(burst)
        val sr = result.superResolved
        val fallback = BilinearUpsampler.upsample2x(result.merged)

        // Columns >= width-9 map to output >= 2*(width-9); assert the odd columns in the far
        // strip, safely beyond any burst reach (and beyond the bilinear splat kernel's spill).
        val firstUncoveredCol = sr.width - 12
        var checked = 0
        for (y in 0 until sr.height) {
            for (x in firstUncoveredCol until sr.width) {
                if (x % 2 == 1) {
                    val i = y * sr.width + x
                    assertEquals(
                        "uncovered border texel ($x, $y) should equal the bilinear fallback",
                        fallback.argb[i],
                        sr.argb[i],
                    )
                    checked++
                }
            }
        }
        assertTrue("expected to exercise fallback texels", checked > sr.height)
    }

    private fun regionLumaMean(frame: Frame, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val width = frame.width
        val argb = frame.argb
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
                val p = argb[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private companion object {
        const val SEED = 0xC0FFEEL
        const val GHOST_SEED = 0xBEEFL
    }
}
