package com.poc.camera.compare

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDownsamplerTest {

    @Test
    fun sourceSmallerThanTargetNeedsNoDownsampling() {
        assertEquals(1, ImageDownsampler.calculateInSampleSize(800, 600, 2048, 2048))
    }

    @Test
    fun sourceExactlyEqualToTargetNeedsNoDownsampling() {
        assertEquals(1, ImageDownsampler.calculateInSampleSize(2048, 2048, 2048, 2048))
    }

    @Test
    fun sourceDoubleTargetOnBothDimensionsHalves() {
        assertEquals(2, ImageDownsampler.calculateInSampleSize(4096, 4096, 2048, 2048))
    }

    @Test
    fun sourceFourTimesTargetOnBothDimensionsQuarters() {
        assertEquals(4, ImageDownsampler.calculateInSampleSize(8192, 8192, 2048, 2048))
    }

    @Test
    fun resultIsBoundByTheLargerRequiredDimension() {
        // 4000x3000 source against a 2048x2048 target: width is the binding constraint,
        // and only a power-of-two sample size that keeps BOTH dimensions >= target is valid.
        val sampleSize = ImageDownsampler.calculateInSampleSize(4000, 3000, 2048, 2048)
        assertEquals(1, sampleSize)
    }

    @Test
    fun nonPowerOfTwoSourceStillYieldsAPowerOfTwoSampleSize() {
        // 12MP-ish photo (4032x3024) downsampled toward a 2048x2048 budget.
        val sampleSize = ImageDownsampler.calculateInSampleSize(4032, 3024, 2048, 2048)
        assertEquals(0, sampleSize and (sampleSize - 1))
    }

    @Test
    fun asymmetricSourceStopsDoublingOnceTheNarrowerDimensionWouldUndershoot() {
        // Tall, narrow source: width (150 half) would drop below the 100 target on the
        // next doubling even though height (1000 half) still has plenty of room - the
        // algorithm requires BOTH half-dimensions to clear the target, so width binds.
        val sampleSize = ImageDownsampler.calculateInSampleSize(300, 2000, 100, 100)
        assertEquals(2, sampleSize)
    }

    @Test
    fun zeroOrNegativeSourceDimensionsNeverDownsample() {
        assertEquals(1, ImageDownsampler.calculateInSampleSize(0, 0, 2048, 2048))
    }
}
