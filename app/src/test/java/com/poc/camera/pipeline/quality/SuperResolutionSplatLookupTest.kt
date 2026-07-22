package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.BilinearUpsampler
import com.poc.camera.pipeline.BoxBlur
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.FrameAligner
import com.poc.camera.pipeline.GhostRejector
import com.poc.camera.pipeline.KernelSplat
import com.poc.camera.pipeline.LumaPlane
import com.poc.camera.pipeline.Luminance
import com.poc.camera.pipeline.RobustFrameMerger
import com.poc.camera.pipeline.SubPixelSampler
import com.poc.camera.pipeline.SuperResolution
import com.poc.camera.pipeline.TileAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Regression gate for the splat-side offset lookup (issue #125). [TileAligner.TileOffsets]
 * is a field over REFERENCE coordinates, but the splat used to evaluate it at FRAME
 * coordinates. The zero-global-shift golden fixtures cannot catch that: a pure translation
 * yields a (near-)constant field, which returns the same value at any lookup coordinate.
 * This gate therefore runs SR on [SyntheticScenes.resolutionChartShiftedWarpedBurst] -- a
 * several-pixel global shift (so frame and reference coordinates genuinely differ)
 * combined with a smooth shear (so the field genuinely varies) -- and proves:
 *
 *  1. the fixed reference-coordinate lookup still recovers the above-Nyquist band beyond
 *     the same 3x floor [SuperResolutionGoldenTest] gates on;
 *  2. the OLD frame-coordinate lookup (replicated verbatim below, everything else equal)
 *     measurably degrades the recovery -- proof the fix bites.
 *
 * Measured at SEED (2026-07-23): fixed above-Nyquist amplitude 18.39 (recovery factor
 * 3.96x over bilinear 4.64) vs 4.31 with the frame-coordinate lookup (factor 0.93x --
 * BELOW bilinear: the mislocated splats destroy the band outright).
 */
class SuperResolutionSplatLookupTest {

    @Test
    fun shiftedWarpedBurstStaysAboveTheGoldenRecoveryFloor() {
        val burst = SyntheticScenes.resolutionChartShiftedWarpedBurst(SEED)
        val result = SuperResolution.superResolve(burst)
        // Every frame must survive rejection, or the scenario would be quietly weakened.
        assertEquals(burst.size, result.usedFrameCount)

        val above = SyntheticScenes.srAboveNyquistBandBounds()
        val period = SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD
        val srAmplitude = SuperResolutionQualityReport.barAmplitude(result.superResolved, above, period)
        val bilinearAmplitude =
            SuperResolutionQualityReport.barAmplitude(BilinearUpsampler.upsample2x(burst.first()), above, period)
        val factor = srAmplitude / bilinearAmplitude

        println(
            "[sr-splat-lookup] shifted+warped fixed amplitude %.2f vs bilinear %.2f -> factor %.2fx"
                .format(srAmplitude, bilinearAmplitude, factor),
        )
        assertTrue(
            "with a global shift and shear the fixed lookup must keep the recovery factor " +
                "$factor above the 3x golden floor (SR $srAmplitude vs bilinear $bilinearAmplitude)",
            factor > 3.0,
        )
    }

    @Test
    fun fixedLookupBeatsTheOldFrameCoordinateLookup() {
        val burst = SyntheticScenes.resolutionChartShiftedWarpedBurst(SEED)
        val above = SyntheticScenes.srAboveNyquistBandBounds()
        val period = SyntheticScenes.SR_ABOVE_NYQUIST_PERIOD

        val fixed = SuperResolution.superResolve(burst).superResolved
        val old = superResolveWithFrameCoordinateLookup(burst)
        val fixedAmplitude = SuperResolutionQualityReport.barAmplitude(fixed, above, period)
        val oldAmplitude = SuperResolutionQualityReport.barAmplitude(old, above, period)

        println(
            "[sr-splat-lookup] above-Nyquist amplitude fixed %.2f vs old frame-coordinate lookup %.2f"
                .format(fixedAmplitude, oldAmplitude),
        )
        // The old lookup mislocates every splat by (field slope * frame-vs-reference
        // displacement), decohering the above-Nyquist phase; measured 18.39 vs 4.31, so a
        // 2x floor leaves generous margin while still requiring the fix to clearly bite.
        assertTrue(
            "fixed amplitude $fixedAmplitude must beat the old frame-coordinate lookup " +
                "$oldAmplitude at least twofold",
            fixedAmplitude > oldAmplitude * 2.0,
        )
    }

    // --- old-lookup replica ----------------------------------------------------------
    //
    // [SuperResolution.superResolve] replicated op for op (same alignment, blurs, merge,
    // gating, fallback and constants) with the ONE difference under test: the splat and
    // sigma lookups evaluate the offset field at FRAME coordinates -- the pre-fix code --
    // instead of resolving the reference position. Keeping everything else identical
    // isolates the lookup as the only variable the comparison measures.

    private fun superResolveWithFrameCoordinateLookup(frames: List<Frame>): Frame {
        val reference = frames.first()
        val width = reference.width
        val height = reference.height

        val alignments = FrameAligner().align(frames)
        val accepted = alignments.map { it.accepted }
        val tileAligner = TileAligner(subPixelDeadZone = 0.0)
        val referenceLuma = Luminance.extract(reference)
        val alignRefLuma = blurLumaPlane(reference, referenceLuma)
        val tileOffsets = frames.mapIndexed { index, frame ->
            if (index == 0 || !accepted[index]) {
                null
            } else {
                val frameLuma = Luminance.extract(frame)
                tileAligner.refine(alignRefLuma, blurLumaPlane(frame, frameLuma), alignments[index].dx, alignments[index].dy)
            }
        }
        val ghostRejector = GhostRejector()
        val merged = RobustFrameMerger.merge(frames, accepted, tileOffsets, ghostRejector)
        val refBlur = blurredLuma(reference, referenceLuma)

        val outWidth = width * SuperResolution.SCALE
        val outHeight = height * SuperResolution.SCALE
        val texels = outWidth * outHeight
        val sumR = FloatArray(texels)
        val sumG = FloatArray(texels)
        val sumB = FloatArray(texels)
        val sumW = FloatArray(texels)

        for (index in frames.indices) {
            if (!accepted[index]) continue
            val frame = frames[index]
            if (index == 0) {
                splatReference(reference, sumR, sumG, sumB, sumW, outWidth, outHeight)
            } else {
                val offsets = tileOffsets[index] ?: continue
                val frameBlur = blurredLuma(frame, null)
                val sigma = estimateSigmaOldLookup(frameBlur, refBlur, offsets, width, height, ghostRejector)
                splatFrameOldLookup(frame, offsets, refBlur, frameBlur, sigma, sumR, sumG, sumB, sumW, outWidth, outHeight)
            }
        }

        val out = IntArray(texels)
        val scratch = DoubleArray(3)
        for (y in 0 until outHeight) {
            val rowBase = y * outWidth
            for (x in 0 until outWidth) {
                val i = rowBase + x
                val totalWeight = sumW[i]
                if (totalWeight > WEIGHT_EPS) {
                    val r = (sumR[i] / totalWeight).roundToInt().coerceIn(0, 255)
                    val g = (sumG[i] / totalWeight).roundToInt().coerceIn(0, 255)
                    val b = (sumB[i] / totalWeight).roundToInt().coerceIn(0, 255)
                    out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    SubPixelSampler.sampleRgb(merged, x / 2.0, y / 2.0, scratch)
                    val r = scratch[0].roundToInt().coerceIn(0, 255)
                    val g = scratch[1].roundToInt().coerceIn(0, 255)
                    val b = scratch[2].roundToInt().coerceIn(0, 255)
                    out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Frame(outWidth, outHeight, out, reference.timestampMillis)
    }

    private fun splatReference(
        reference: Frame,
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ) {
        for (fy in 0 until reference.height) {
            val rowBase = fy * reference.width
            for (fx in 0 until reference.width) {
                val pixel = reference.argb[rowBase + fx]
                KernelSplat.splat(
                    sumR, sumG, sumB, sumW, outWidth, outHeight,
                    (SuperResolution.SCALE * fx).toDouble(), (SuperResolution.SCALE * fy).toDouble(),
                    ((pixel shr 16) and 0xFF).toFloat(),
                    ((pixel shr 8) and 0xFF).toFloat(),
                    (pixel and 0xFF).toFloat(),
                    1f,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun splatFrameOldLookup(
        frame: Frame,
        offsets: TileAligner.TileOffsets,
        refBlur: DoubleArray,
        frameBlur: DoubleArray,
        sigma: Double,
        sumR: FloatArray,
        sumG: FloatArray,
        sumB: FloatArray,
        sumW: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ) {
        val width = frame.width
        val height = frame.height
        val ghostRejector = GhostRejector()
        for (fy in 0 until height) {
            val rowBase = fy * width
            for (fx in 0 until width) {
                // THE PRE-FIX LOOKUP: the reference-coordinate field read at frame coords.
                val refX = fx - offsets.offsetXAt(fx, fy)
                val refY = fy - offsets.offsetYAt(fx, fy)
                val refBlurAt = samplePlane(refBlur, width, height, refX, refY)
                val weight = ghostRejector.weight(frameBlur[rowBase + fx] - refBlurAt, sigma)
                if (weight <= 0.0) continue
                val pixel = frame.argb[rowBase + fx]
                KernelSplat.splat(
                    sumR, sumG, sumB, sumW, outWidth, outHeight,
                    SuperResolution.SCALE * refX, SuperResolution.SCALE * refY,
                    ((pixel shr 16) and 0xFF).toFloat(),
                    ((pixel shr 8) and 0xFF).toFloat(),
                    (pixel and 0xFF).toFloat(),
                    weight.toFloat(),
                )
            }
        }
    }

    private fun estimateSigmaOldLookup(
        frameBlur: DoubleArray,
        refBlur: DoubleArray,
        offsets: TileAligner.TileOffsets,
        width: Int,
        height: Int,
        ghostRejector: GhostRejector,
    ): Double {
        val cols = (width + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val rows = (height + SIGMA_SAMPLE_STRIDE - 1) / SIGMA_SAMPLE_STRIDE
        val diffs = DoubleArray(cols * rows)
        var k = 0
        var y = 0
        while (y < height) {
            val rowBase = y * width
            var x = 0
            while (x < width) {
                val refX = x - offsets.offsetXAt(x, y)
                val refY = y - offsets.offsetYAt(x, y)
                diffs[k++] = frameBlur[rowBase + x] - samplePlane(refBlur, width, height, refX, refY)
                x += SIGMA_SAMPLE_STRIDE
            }
            y += SIGMA_SAMPLE_STRIDE
        }
        return ghostRejector.estimateSigma(diffs)
    }

    private fun blurredLuma(frame: Frame, precomputed: LumaPlane?): DoubleArray {
        val luma = precomputed ?: Luminance.extract(frame)
        val src = DoubleArray(luma.values.size) { luma.values[it].toDouble() }
        return BoxBlur.blur(src, frame.width, frame.height, LOW_PASS_BLUR_RADIUS)
    }

    private fun blurLumaPlane(frame: Frame, luma: LumaPlane): LumaPlane {
        val src = DoubleArray(luma.values.size) { luma.values[it].toDouble() }
        val blurred = BoxBlur.blur(src, frame.width, frame.height, LOW_PASS_BLUR_RADIUS)
        return LumaPlane(frame.width, frame.height, IntArray(blurred.size) { blurred[it].roundToInt() })
    }

    private fun samplePlane(plane: DoubleArray, width: Int, height: Int, x: Double, y: Double): Double {
        val maxX = width - 1
        val maxY = height - 1
        val cx = x.coerceIn(0.0, maxX.toDouble())
        val cy = y.coerceIn(0.0, maxY.toDouble())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0
        val v00 = plane[y0 * width + x0]
        val v10 = plane[y0 * width + x1]
        val v01 = plane[y1 * width + x0]
        val v11 = plane[y1 * width + x1]
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return top + (bottom - top) * fy
    }

    private companion object {
        const val SEED = 0xC0FFEEL

        // Mirrors of SuperResolution's private constants; the replica must track them.
        const val WEIGHT_EPS = 1e-3
        const val LOW_PASS_BLUR_RADIUS = 2
        const val SIGMA_SAMPLE_STRIDE = 4
    }
}
