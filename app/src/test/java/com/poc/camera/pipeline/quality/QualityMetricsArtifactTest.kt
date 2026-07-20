package com.poc.camera.pipeline.quality

import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.NightPipeline
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes the measured pipeline quality metrics to a JSON report under the build
 * directory so CI can upload it as an artifact on every run. The numbers are the
 * same deterministic actuals the golden suites gate on; this file exists to make
 * their evolution inspectable across commits, not to assert thresholds (the
 * golden tests own that).
 */
class QualityMetricsArtifactTest {

    @Test
    fun writeMetricsReport() {
        val still = PipelineQualityReport.measureAll(SEED)
        val rendition = SyntheticScenes.names.map { name ->
            val clean = SyntheticScenes.clean(name)
            val burst = SyntheticScenes.burst(name, SEED, PipelineQualityReport.DEFAULT_BURST_SIZE)
            val merged = com.poc.camera.pipeline.BurstMergePipeline.merge(burst).merged
            val finished = FinishingPipeline.apply(merged, com.poc.camera.pipeline.FinishingParams.RENDITION)
            PipelineQualityReport.SceneMetrics(
                scene = name,
                psnr = Psnr.between(finished, clean),
                ssim = Ssim.between(finished, clean),
                mae = Mae.between(finished, clean),
            )
        }
        val hdr = HdrPipelineQualityReport.measure(HDR_SEED)
        val nightClean = SyntheticScenes.nightClean()
        val nightBurst = SyntheticScenes.nightBurst(SEED, NIGHT_FRAMES)
        val nightMerged = NightPipeline.merge(nightBurst).merged
        val nightFinished = FinishingPipeline.apply(nightMerged, com.poc.camera.pipeline.FinishingParams.NIGHT)

        val json = buildString {
            appendLine("{")
            appendLine("  \"seed\": \"0x${SEED.toString(16).uppercase()}\",")
            appendLine("  \"still_default\": [")
            appendLine(still.joinToString(",\n") { sceneJson(it) })
            appendLine("  ],")
            appendLine("  \"still_rendition_vs_clean\": [")
            appendLine(rendition.joinToString(",\n") { sceneJson(it) })
            appendLine("  ],")
            appendLine("  \"hdr\": { \"psnr\": ${fmt(hdr.psnr)}, \"ssim\": ${fmt(hdr.ssim)}, \"mae\": ${fmt(hdr.mae)} },")
            appendLine(
                "  \"night\": { \"psnr\": ${fmt(Psnr.between(nightFinished, nightClean))}, " +
                    "\"ssim\": ${fmt(Ssim.between(nightFinished, nightClean))}, " +
                    "\"mae\": ${fmt(Mae.between(nightFinished, nightClean))} }",
            )
            appendLine("}")
        }

        val out = File("build/reports/quality-metrics/quality-metrics.json")
        out.parentFile.mkdirs()
        out.writeText(json)
        assertTrue(out.length() > 0)
    }

    private fun sceneJson(m: PipelineQualityReport.SceneMetrics): String =
        "    { \"scene\": \"${m.scene}\", \"psnr\": ${fmt(m.psnr)}, \"ssim\": ${fmt(m.ssim)}, \"mae\": ${fmt(m.mae)} }"

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)

    private companion object {
        const val SEED = 0xC0FFEEL
        const val HDR_SEED = 0xDEC1L
        const val NIGHT_FRAMES = 12
    }
}
