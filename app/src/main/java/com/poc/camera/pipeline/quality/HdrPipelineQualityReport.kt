package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.HdrMergePipeline

/**
 * Runs the exposure-bracketed HDR pipeline against the synthetic HDR scene and
 * reports quality against the tone-mapped reference truth.
 *
 * It merges the bracketed noisy burst with [HdrMergePipeline], finishes the fused
 * result with [FinishingPipeline] DEFAULT params, and scores it against
 * [SyntheticScenes.hdrToneMappedTruth] with [Psnr], [Ssim] and [Mae]. It also
 * measures the genuine dynamic-range property: the mean absolute error in the
 * truth's darkest and brightest deciles, both for the fused result and for the best
 * single (finished) exposure over the same region. Fusion is expected to beat the
 * best single exposure in both deciles because each single exposure clips one end.
 *
 * Fully deterministic for a fixed seed. Pure Kotlin, no Android dependencies.
 */
object HdrPipelineQualityReport {

    /** Fraction of pixels forming the shadow / highlight regions scored separately. */
    const val DECILE_FRACTION = 0.1

    /**
     * @property psnr full-frame PSNR of the finished fused result vs. truth.
     * @property ssim full-frame SSIM of the finished fused result vs. truth.
     * @property mae full-frame MAE of the finished fused result vs. truth.
     * @property fusedShadowMae fused MAE over the truth's darkest decile.
     * @property bestSingleShadowMae best single-exposure MAE over that decile.
     * @property fusedHighlightMae fused MAE over the truth's brightest decile.
     * @property bestSingleHighlightMae best single-exposure MAE over that decile.
     */
    data class HdrMetrics(
        val psnr: Double,
        val ssim: Double,
        val mae: Double,
        val fusedShadowMae: Double,
        val bestSingleShadowMae: Double,
        val fusedHighlightMae: Double,
        val bestSingleHighlightMae: Double,
    )

    fun measure(seed: Long): HdrMetrics {
        val truth = SyntheticScenes.hdrToneMappedTruth()
        val burst = SyntheticScenes.hdrBurst(seed)

        val result = HdrMergePipeline.merge(burst.frames, burst.evs)
        val fused = FinishingPipeline.apply(result.fused)
        val perEvFinished = result.perEvMerged.map { FinishingPipeline.apply(it.merged) }

        val shadowMask = decileMask(truth, brightest = false)
        val highlightMask = decileMask(truth, brightest = true)

        return HdrMetrics(
            psnr = Psnr.between(fused, truth),
            ssim = Ssim.between(fused, truth),
            mae = Mae.between(fused, truth),
            fusedShadowMae = maskedMae(fused, truth, shadowMask),
            bestSingleShadowMae = perEvFinished.minOf { maskedMae(it, truth, shadowMask) },
            fusedHighlightMae = maskedMae(fused, truth, highlightMask),
            bestSingleHighlightMae = perEvFinished.minOf { maskedMae(it, truth, highlightMask) },
        )
    }

    /**
     * A boolean mask selecting the darkest (or [brightest]) [DECILE_FRACTION] of
     * pixels by the truth's luma. The threshold is the corresponding percentile of
     * the sorted luma, so ties at the boundary are all included.
     */
    private fun decileMask(truth: Frame, brightest: Boolean): BooleanArray {
        val luma = IntArray(truth.argb.size) { luminance(truth.argb[it]) }
        val sorted = luma.sortedArray()
        val count = (luma.size * DECILE_FRACTION).toInt().coerceAtLeast(1)
        return if (brightest) {
            val threshold = sorted[luma.size - count]
            BooleanArray(luma.size) { luma[it] >= threshold }
        } else {
            val threshold = sorted[count - 1]
            BooleanArray(luma.size) { luma[it] <= threshold }
        }
    }

    /** Mean absolute per-channel error between [a] and [b] over the [mask]ed pixels. */
    private fun maskedMae(a: Frame, b: Frame, mask: BooleanArray): Double {
        val pa = a.argb
        val pb = b.argb
        var sum = 0L
        var count = 0L
        for (i in pa.indices) {
            if (!mask[i]) continue
            val x = pa[i]
            val y = pb[i]
            sum += kotlin.math.abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)).toLong()
            sum += kotlin.math.abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)).toLong()
            sum += kotlin.math.abs((x and 0xFF) - (y and 0xFF)).toLong()
            count++
        }
        return if (count == 0L) 0.0 else sum.toDouble() / (count.toDouble() * 3.0)
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (299 * r + 587 * g + 114 * b + 500) / 1000
    }
}
