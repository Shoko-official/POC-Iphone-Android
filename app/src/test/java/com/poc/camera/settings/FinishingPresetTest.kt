package com.poc.camera.settings

import com.poc.camera.pipeline.FinishingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FinishingPresetTest {

    @Test
    fun naturalIsExactlyTheTestedRenditionProfile() {
        assertEquals(FinishingParams.RENDITION, FinishingPreset.Natural.params)
    }

    @Test
    fun vividBuildsOnRenditionWithStrongerColourAndLocalContrast() {
        val params = FinishingPreset.Vivid.params

        assertEquals(1.16, params.saturation, 0.0)
        assertEquals(1.09, params.contrast, 0.0)
        assertEquals(0.45, params.localContrast, 0.0)
        // Everything not called out by the preset stays on the RENDITION baseline.
        assertEquals(FinishingParams.RENDITION.detailEnhance, params.detailEnhance, 0.0)
        assertEquals(FinishingParams.RENDITION.shadowsLift, params.shadowsLift, 0.0)
        assertEquals(FinishingParams.RENDITION.highlightRolloff, params.highlightRolloff, 0.0)
        assertEquals(FinishingParams.RENDITION.chromaDenoise, params.chromaDenoise, 0.0)
        assertEquals(FinishingParams.RENDITION.whiteBalance, params.whiteBalance, 0.0)
    }

    @Test
    fun detailBuildsOnRenditionWithStrongerSharpeningAndRestrainedColour() {
        val params = FinishingPreset.Detail.params

        assertEquals(0.8, params.detailEnhance, 0.0)
        assertEquals(0.40, params.localContrast, 0.0)
        assertEquals(1.04, params.saturation, 0.0)
        // Contrast and tone are untouched by this preset.
        assertEquals(FinishingParams.RENDITION.contrast, params.contrast, 0.0)
        assertEquals(FinishingParams.RENDITION.shadowsLift, params.shadowsLift, 0.0)
        assertEquals(FinishingParams.RENDITION.highlightRolloff, params.highlightRolloff, 0.0)
    }

    @Test
    fun everyPresetDiffersFromEveryOtherPreset() {
        val presets = FinishingPreset.entries
        for (i in presets.indices) {
            for (j in presets.indices) {
                if (i != j) {
                    assertNotEquals(
                        "${presets[i]} and ${presets[j]} must map to different params",
                        presets[i].params,
                        presets[j].params,
                    )
                }
            }
        }
    }
}
