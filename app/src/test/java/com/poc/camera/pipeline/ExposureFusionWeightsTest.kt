package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureFusionWeightsTest {

    private fun solid(r: Int, g: Int, b: Int): Frame {
        val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Frame(2, 2, IntArray(4) { pixel }, 0L)
    }

    @Test
    fun wellExposednessPeaksAtMidGrey() {
        val mid = ExposureFusionWeights.wellExposedness(127.5)
        assertEquals(1.0, mid, 1e-9)
        assertTrue(ExposureFusionWeights.wellExposedness(0.0) < mid)
        assertTrue(ExposureFusionWeights.wellExposedness(255.0) < mid)
    }

    @Test
    fun wellExposednessIsSymmetricAroundMid() {
        assertEquals(
            ExposureFusionWeights.wellExposedness(100.0),
            ExposureFusionWeights.wellExposedness(155.0),
            1e-9,
        )
    }

    @Test
    fun overExposedFrameDropsClippedHighlights() {
        val clipped = ExposureFusionWeights.weightMap(solid(255, 255, 255), ev = 2.0)
        assertTrue(clipped.all { it == 0.0 })

        val oneChannelClipped = ExposureFusionWeights.weightMap(solid(128, 128, 250), ev = 2.0)
        assertTrue(oneChannelClipped.all { it == 0.0 })
    }

    @Test
    fun underExposedFrameDropsCrushedShadows() {
        val crushed = ExposureFusionWeights.weightMap(solid(0, 0, 0), ev = -2.0)
        assertTrue(crushed.all { it == 0.0 })

        val oneChannelCrushed = ExposureFusionWeights.weightMap(solid(5, 128, 128), ev = -2.0)
        assertTrue(oneChannelCrushed.all { it == 0.0 })
    }

    @Test
    fun referenceExposureAppliesNoSaturationPenalty() {
        // At EV 0 even a clipped or crushed pixel keeps its (non-zero) well-exposedness.
        val clipped = ExposureFusionWeights.weightMap(solid(255, 255, 255), ev = 0.0)
        assertTrue(clipped.all { it > 0.0 })
        val crushed = ExposureFusionWeights.weightMap(solid(0, 0, 0), ev = 0.0)
        assertTrue(crushed.all { it > 0.0 })
    }

    @Test
    fun wellExposedPixelKeepsWeightEvenWhenTaggedOverExposed() {
        // A mid-grey pixel is not clipped, so an over-exposed tag leaves it untouched.
        val map = ExposureFusionWeights.weightMap(solid(128, 128, 128), ev = 2.0)
        val expected = ExposureFusionWeights.wellExposedness(128.0)
        assertTrue(map.all { it == expected })
    }
}
