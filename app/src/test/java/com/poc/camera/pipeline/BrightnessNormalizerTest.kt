package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessNormalizerTest {

    private fun solid(value: Int, size: Int = 8): Frame {
        val v = value.coerceIn(0, 255)
        val argb = IntArray(size * size) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Frame(size, size, argb, timestampMillis = 0L)
    }

    private fun meanLuma(frame: Frame): Double {
        var sum = 0.0
        for (p in frame.argb) {
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }
        return sum / frame.argb.size
    }

    @Test
    fun matchGainBringsFrameTowardReference() {
        val reference = solid(120)
        val frame = solid(100)
        val gain = BrightnessNormalizer.matchGain(frame, reference, 0.7, 1.4, 0.05)
        assertEquals(1.2, gain, 1e-6)
    }

    @Test
    fun matchGainIsClampedToUpperBound() {
        val reference = solid(200)
        val frame = solid(50) // raw gain 4.0, must clamp to maxGain
        val gain = BrightnessNormalizer.matchGain(frame, reference, 0.7, 1.4, 0.05)
        assertEquals(1.4, gain, 1e-9)
    }

    @Test
    fun matchGainIsClampedToLowerBound() {
        val reference = solid(50)
        val frame = solid(200) // raw gain 0.25, must clamp to minGain
        val gain = BrightnessNormalizer.matchGain(frame, reference, 0.7, 1.4, 0.05)
        assertEquals(0.7, gain, 1e-9)
    }

    @Test
    fun matchGainIsOneForDegenerateBlackFrame() {
        val reference = solid(120)
        val frame = solid(0)
        assertEquals(1.0, BrightnessNormalizer.matchGain(frame, reference, 0.7, 1.4, 0.05), 0.0)
    }

    @Test
    fun applyGainScalesChannelsAndClampsToWhite() {
        val frame = solid(200)
        val brightened = BrightnessNormalizer.applyGain(frame, 1.4)
        // 200 * 1.4 = 280 -> clamps to 255.
        assertEquals(255, (brightened.argb[0] shr 16) and 0xFF)
    }

    @Test
    fun normalizeMovesTrimmedMeanTowardReferenceWithinBounds() {
        val reference = solid(130)
        val frame = solid(110)
        val normalized = BrightnessNormalizer.normalize(frame, reference, NightMergeParams())
        // Gain 1.18 is within [0.7, 1.4], so the frame's mean should land on the reference.
        assertEquals(meanLuma(reference), meanLuma(normalized), 1.0)
    }

    @Test
    fun trimmedMeanIgnoresBrightOutliers() {
        // A dark field with a few blown-out pixels: the trimmed mean should stay near the
        // dark background, unlike the plain mean.
        val size = 10
        val argb = IntArray(size * size) { (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20 }
        for (i in 0 until 5) argb[i] = (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
        val frame = Frame(size, size, argb, 0L)
        val trimmed = BrightnessNormalizer.trimmedMeanLuma(frame, 0.1)
        assertTrue("trimmed mean $trimmed should stay near the dark background", trimmed < 25.0)
    }
}
