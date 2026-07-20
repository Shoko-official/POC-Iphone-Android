package com.poc.camera.compare

import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.quality.Mae
import com.poc.camera.pipeline.quality.Psnr
import com.poc.camera.pipeline.quality.Ssim

data class CompareMetricsResult(
    val psnrDb: Double,
    val ssim: Double,
    val mae: Double,
)

/**
 * PSNR/SSIM/MAE between the processed capture and the reference (unprocessed
 * merge input, or an imported external photo). An external reference rarely
 * shares the app's exact framing and resolution, so a dimension mismatch is
 * reported as "not comparable" (`null`) rather than an error - the caller falls
 * back to visual-only inspection and should treat any returned numbers as
 * indicative rather than a strict parity score when the reference did not
 * originate from this app. Pure math, no Android dependencies; walks every pixel
 * of both frames multiple times so callers should run this off the main thread.
 */
object CompareMetrics {

    private const val MIN_COMPARABLE_DIMENSION = 8

    fun compute(processed: Frame, reference: Frame): CompareMetricsResult? {
        if (processed.width != reference.width || processed.height != reference.height) return null
        if (processed.width < MIN_COMPARABLE_DIMENSION || processed.height < MIN_COMPARABLE_DIMENSION) return null
        return CompareMetricsResult(
            psnrDb = Psnr.between(processed, reference),
            ssim = Ssim.between(processed, reference),
            mae = Mae.between(processed, reference),
        )
    }
}
