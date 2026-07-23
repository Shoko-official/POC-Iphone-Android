package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A uniform frame of one colour. */
    private fun solid(width: Int, height: Int, r: Int, g: Int, b: Int): Frame =
        Frame(width, height, IntArray(width * height) { rgb(r, g, b) }, 0L)

    /**
     * A field of near-neutral gray pixels [neutral] with a deterministic low-amplitude
     * per-channel jitter, plus [outliers] pixels of a fully saturated colour scattered
     * in. The trimmed gray-world estimate must ignore the saturated outliers.
     */
    private fun grayFieldWithOutliers(
        size: Int,
        neutralR: Int,
        neutralG: Int,
        neutralB: Int,
        outlierEvery: Int,
    ): Frame {
        var state = 0x1234_5678L
        fun jitter(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 20) % 7L).toInt() - 3 // [-3, 3]
        }
        val argb = IntArray(size * size) { i ->
            if (i % outlierEvery == 0) {
                rgb(250, 5, 5) // vivid red outlier (high saturation)
            } else {
                rgb(
                    (neutralR + jitter()).coerceIn(0, 255),
                    (neutralG + jitter()).coerceIn(0, 255),
                    (neutralB + jitter()).coerceIn(0, 255),
                )
            }
        }
        return Frame(size, size, argb, 0L)
    }

    /**
     * A warm-surface-dominant scene with an embedded TRUE-NEUTRAL patch (issue #175):
     * [woodFraction] of the pixels are a low-saturation warm wood tone (admitted by the
     * gray-world saturation gate) and the rest are genuine neutral gray. Deterministic
     * jitter keeps both populations realistic without randomness.
     */
    private fun warmSceneWithNeutralPatch(size: Int, woodFraction: Double): Frame {
        var state = 0x0BADC0DEL
        fun jitter(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 20) % 7L).toInt() - 3 // [-3, 3]
        }
        val n = size * size
        val woodCount = (n * woodFraction).toInt()
        val argb = IntArray(n) { i ->
            if (i < woodCount) {
                // Warm oak: sat = (150-92)/150 ~= 0.39 < GRAY_SATURATION_MAX, mid-luma.
                rgb(
                    (150 + jitter()).coerceIn(0, 255),
                    (118 + jitter()).coerceIn(0, 255),
                    (92 + jitter()).coerceIn(0, 255),
                )
            } else {
                // True neutral gray (a checkerboard/gray card in the scene).
                rgb(
                    (128 + jitter()).coerceIn(0, 255),
                    (128 + jitter()).coerceIn(0, 255),
                    (128 + jitter()).coerceIn(0, 255),
                )
            }
        }
        return Frame(size, size, argb, 0L)
    }

    /**
     * Issue #175 invariant: a scene dominated by a warm SURFACE (not a warm illuminant)
     * that also contains genuine neutral content must not have that true neutral pushed
     * PAST neutral toward blue. The neutral patch is the scene's own illuminant probe -
     * when a meaningful near-neutral population reads as balanced, the estimator must not
     * chase the warm surface average into a blue cast.
     */
    @Test
    fun warmDominantSceneDoesNotPushTrueNeutralsPastNeutral() {
        val frame = warmSceneWithNeutralPatch(size = 96, woodFraction = 0.84)
        val gains = WhiteBalance.estimateGains(frame)
        // Apply the estimate to a perfect neutral: it must stay neutral. Blue-ward
        // overshoot ((b - r) after gains) beyond ~2 codes is the cold cast the real
        // A/B pair exhibited.
        val r = 128.0 * gains.rGain
        val b = 128.0 * gains.bGain
        assertTrue(
            "true neutral pushed blue-ward by ${b - r} codes (rGain=${gains.rGain} bGain=${gains.bGain})",
            b - r <= 2.0,
        )
    }

    @Test
    fun grayWorldExcludesSaturatedOutliers() {
        // A warm-cast neutral surface (gray * 1.25R / 1.0G / 0.8B) with vivid red
        // outliers sprinkled in. The trimmed gray-world cue must recover the cast mean
        // (~150, 120, 96), NOT be dragged red by the outliers.
        val frame = grayFieldWithOutliers(64, neutralR = 150, neutralG = 120, neutralB = 96, outlierEvery = 17)
        val cue = WhiteBalance.grayWorldCue(frame)

        assertTrue("cue must have found neutral samples", cue.count > 0)
        // Means are close to the neutral surface, unpolluted by the red (250,5,5) outliers.
        assertEquals(150.0, cue.meanR, 4.0)
        assertEquals(120.0, cue.meanG, 4.0)
        assertEquals(96.0, cue.meanB, 4.0)
    }

    @Test
    fun grayWorldRecoversWarmCastGains() {
        // Pure cast neutral (no outliers): gains must neutralise it. Warm cast lowers the
        // red gain (<1) and raises the blue gain (>1), green pinned to 1.
        val frame = solid(48, 48, r = 150, g = 120, b = 96)
        val gains = WhiteBalance.estimateGains(frame)

        assertEquals(1.0, gains.gGain, 0.0)
        assertTrue("warm cast must pull red gain below 1: ${gains.rGain}", gains.rGain < 0.98)
        assertTrue("warm cast must push blue gain above 1: ${gains.bGain}", gains.bGain > 1.02)
        // Sanity: applying the gains roughly neutralises the surface.
        val corrected = WhiteBalance.apply(frame, strength = 1.0)
        val p = corrected.argb[corrected.argb.size / 2]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        assertTrue("corrected surface should be closer to neutral: $r,$g,$b", abs(r - b) < abs(150 - 96))
    }

    @Test
    fun highlightCuePicksTheBrightNeutralBand() {
        // Dark saturated background (excluded from the highlight band) with a bright
        // near-white cast band. The highlight cue must report the bright band's colour.
        val size = 64
        val argb = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                argb[y * size + x] = if (y < size - 8) {
                    rgb(120, 20, 20) // dark, saturated -> not in the bright band
                } else {
                    rgb(235, 220, 190) // bright warm near-white -> the illuminant probe
                }
            }
        }
        val cue = WhiteBalance.highlightCue(Frame(size, size, argb, 0L))

        assertTrue("highlight cue must find the bright band", cue.count > 0)
        assertEquals(235.0, cue.meanR, 2.0)
        assertEquals(220.0, cue.meanG, 2.0)
        assertEquals(190.0, cue.meanB, 2.0)
    }

    @Test
    fun highlightCueExcludesClippedChannels() {
        // A bright band with a clipped (255) red channel must be excluded: a clipped
        // channel carries no colour information.
        val size = 32
        val argb = IntArray(size * size) { rgb(255, 240, 230) } // red clipped
        val cue = WhiteBalance.highlightCue(Frame(size, size, argb, 0L))
        assertEquals("clipped-red pixels must be excluded", 0, cue.count)
    }

    @Test
    fun gainsAreClampedToMaxGain() {
        // A strong cast (red high, blue low) demands gains beyond a tight bound; they must
        // clamp to [1/maxGain, maxGain]. The surface stays inside the gray-world
        // saturation gate and below the highlight clip so the estimate is not degenerate.
        val frame = solid(48, 48, r = 180, g = 120, b = 85)
        val maxGain = 1.3
        val gains = WhiteBalance.estimateGains(frame, maxGain = maxGain)
        assertTrue("rGain must be >= 1/maxGain", gains.rGain >= 1.0 / maxGain - 1e-9)
        assertTrue("rGain must be <= maxGain", gains.rGain <= maxGain + 1e-9)
        assertTrue("bGain must be >= 1/maxGain", gains.bGain >= 1.0 / maxGain - 1e-9)
        assertTrue("bGain must be <= maxGain", gains.bGain <= maxGain + 1e-9)
        // This cast is strong enough to hit both bounds.
        assertEquals(1.0 / maxGain, gains.rGain, 1e-9)
        assertEquals(maxGain, gains.bGain, 1e-9)
    }

    @Test
    fun neutralToleranceSnapsWeakCastToIdentity() {
        // A very mild cast (deviation inside the neutral band) must be treated as scene
        // colour, not corrected: gains stay exactly identity.
        val frame = solid(48, 48, r = 132, g = 128, b = 124) // ~3% off neutral
        val gains = WhiteBalance.estimateGains(frame)
        assertEquals(WhiteBalanceGains.IDENTITY, gains)
    }

    @Test
    fun degenerateFrameFallsBackToIdentity() {
        // A frame with no usable near-neutral or near-white content (all fully saturated,
        // near-black) yields identity rather than a guess.
        val frame = solid(48, 48, r = 8, g = 0, b = 0)
        val gains = WhiteBalance.estimateGains(frame)
        assertEquals(WhiteBalanceGains.IDENTITY, gains)
    }

    @Test
    fun strengthBlendsTowardIdentity() {
        // The applied correction scales with strength: half strength moves each channel
        // about half as far as full strength.
        val frame = solid(40, 40, r = 160, g = 120, b = 90)
        val full = WhiteBalance.apply(frame, strength = 1.0)
        val half = WhiteBalance.apply(frame, strength = 0.5)

        val i = frame.argb.size / 2
        val srcR = (frame.argb[i] shr 16) and 0xFF
        val fullR = (full.argb[i] shr 16) and 0xFF
        val halfR = (half.argb[i] shr 16) and 0xFF
        // Half-strength red shift is roughly half the full-strength shift.
        val fullShift = (fullR - srcR).toDouble()
        val halfShift = (halfR - srcR).toDouble()
        assertTrue("full strength must actually shift red", abs(fullShift) > 2.0)
        assertEquals(fullShift / 2.0, halfShift, 1.5)
    }

    @Test
    fun strengthZeroIsANoOpApartFromAlpha() {
        val frame = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(
                (0x80 shl 24) or (200 shl 16) or (40 shl 8) or 60,
                (0x10 shl 24) or (30 shl 16) or (200 shl 8) or 210,
            ),
            timestampMillis = 7L,
        )
        val output = WhiteBalance.apply(frame, strength = 0.0)
        for (i in frame.argb.indices) {
            assertEquals(frame.argb[i] and 0x00FFFFFF, output.argb[i] and 0x00FFFFFF)
            assertEquals(0xFF, (output.argb[i] ushr 24) and 0xFF)
        }
        assertEquals(7L, output.timestampMillis)
    }

    @Test
    fun applyPreservesDimensionsAndForcesOpaque() {
        val frame = solid(3, 2, r = 180, g = 120, b = 90).let {
            // Poison alpha to prove it is forced opaque.
            Frame(it.width, it.height, IntArray(it.argb.size) { i -> it.argb[i] and 0x00FFFFFF }, 5L)
        }
        val output = WhiteBalance.apply(frame, strength = 1.0)
        assertEquals(3, output.width)
        assertEquals(2, output.height)
        assertEquals(5L, output.timestampMillis)
        for (pixel in output.argb) assertEquals(0xFF, (pixel ushr 24) and 0xFF)
    }

    @Test
    fun isDeterministic() {
        val frame = solid(40, 40, r = 170, g = 120, b = 95)
        val first = WhiteBalance.apply(frame, strength = 0.8)
        val second = WhiteBalance.apply(frame, strength = 0.8)
        assertTrue(first.argb.contentEquals(second.argb))
    }

    @Test
    fun rejectsInvalidParameters() {
        val frame = solid(8, 8, r = 128, g = 128, b = 128)
        assertThrows(IllegalArgumentException::class.java) { WhiteBalance.apply(frame, strength = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { WhiteBalance.apply(frame, strength = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { WhiteBalance.estimateGains(frame, maxGain = 0.5) }
    }
}
