package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proofs for [BacklitDetector]: the three histogram fractions, the smoothstepped
 * per-criterion margins and the strength = min(margins) rule, and -- critically -- the
 * false-positive gates that keep it quiet on low-key and crushed high-contrast content.
 *
 * Frames are built from flat luma blocks (via [gray]), so each fraction is exact and the
 * strengths are closed-form. A composited frame of `frac` dark + `frac` bright + rest mid
 * lets each criterion be exercised independently.
 */
class BacklitDetectorTest {

    // --- fractions -----------------------------------------------------------------

    @Test
    fun fractionsCountTheRightBands() {
        // 40% dark (luma 40), 30% bright (luma 200), 30% mid (luma 100).
        val frame = composite(darkFrac = 0.40, brightFrac = 0.30, darkLuma = 40, brightLuma = 200, midLuma = 100)
        val d = BacklitDetector.detect(frame)
        assertEquals(0.40, d.darkFraction, 0.01)
        assertEquals(0.30, d.brightFraction, 0.01)
        assertEquals(0.30, d.midFraction, 0.01)
    }

    @Test
    fun crushedBlackDoesNotCountAsDarkMass() {
        // Pixels below the shadow floor (luma 10) are crushed shadow, not recoverable subject,
        // so they must NOT be counted as dark mass -- this is the high-contrast gate.
        val frame = composite(darkFrac = 0.50, brightFrac = 0.50, darkLuma = 10, brightLuma = 240, midLuma = 100)
        val d = BacklitDetector.detect(frame)
        assertEquals("crushed-black must not register as recoverable dark mass", 0.0, d.darkFraction, 0.001)
    }

    // --- strength / margins matrix -------------------------------------------------

    @Test
    fun strongBacklitSignatureFiresHard() {
        // Plenty of recoverable-shadow subject, plenty of bright background, empty valley.
        val frame = composite(darkFrac = 0.40, brightFrac = 0.55, darkLuma = 40, brightLuma = 200, midLuma = 100)
        val d = BacklitDetector.detect(frame)
        assertTrue("clear backlit signature must fire strongly (was ${d.strength})", d.strength > 0.7)
    }

    @Test
    fun lowKeySceneStaysQuietForLackOfBrightMass() {
        // All recoverable-shadow, no bright mass: a legitimately dark scene the bright-mass
        // requirement must protect. Strength must be exactly 0.
        val frame = composite(darkFrac = 0.80, brightFrac = 0.0, darkLuma = 40, brightLuma = 200, midLuma = 45)
        val d = BacklitDetector.detect(frame)
        assertEquals("low-key scene must not fire", 0.0, d.strength, 0.0)
    }

    @Test
    fun crushedHighContrastSceneStaysQuiet() {
        // Deep-shadow (crushed) beside near-clipped highlight, empty middle: the raw bimodal
        // shape of a high-contrast test chart. The shadow floor keeps the dark band empty, so
        // the dark-mass criterion fails and the strength is exactly 0.
        val frame = composite(darkFrac = 0.50, brightFrac = 0.50, darkLuma = 12, brightLuma = 242, midLuma = 100)
        val d = BacklitDetector.detect(frame)
        assertEquals("crushed high-contrast must not fire", 0.0, d.strength, 0.0)
    }

    @Test
    fun midtoneHeavySceneStaysQuietOnTheValley() {
        // Dark + bright present, but a full midtone body: the valley criterion fails.
        val frame = composite(darkFrac = 0.30, brightFrac = 0.30, darkLuma = 40, brightLuma = 200, midLuma = 110)
        val d = BacklitDetector.detect(frame)
        assertTrue("midtone-heavy scene must be quiet (was ${d.strength})", d.strength < 0.1)
    }

    @Test
    fun strengthIsCappedByTheWeakestCriterion() {
        // Bright mass sits just over its threshold while dark and valley are comfortable, so
        // the strength tracks the (weak) bright margin -- proving it is the MIN of the three.
        // Filler luma 65 sits in NO band (above the dark ceiling, below the valley floor), so it
        // keeps the valley empty and leaves the bright margin as the sole weakest criterion.
        val frame = composite(darkFrac = 0.40, brightFrac = 0.18, darkLuma = 40, brightLuma = 200, midLuma = 65)
        val d = BacklitDetector.detect(frame)
        val brightMargin = smoothstep((d.brightFraction - BacklitDetector.BRIGHT_FRACTION) / BacklitDetector.BRIGHT_RAMP)
        assertEquals("strength must equal the weakest (bright) margin", brightMargin, d.strength, 1e-9)
        assertTrue("weak bright mass must cap the strength below 1", d.strength < 1.0)
    }

    @Test
    fun atThresholdMarginIsZero() {
        // Dark fraction exactly at threshold -> dark margin 0 -> strength 0, even with strong
        // bright mass and an empty valley.
        val frame = composite(
            darkFrac = BacklitDetector.DARK_FRACTION,
            brightFrac = 0.55,
            darkLuma = 40,
            brightLuma = 200,
            midLuma = 100,
        )
        val d = BacklitDetector.detect(frame)
        assertEquals(BacklitDetector.DARK_FRACTION, d.darkFraction, 0.005)
        assertEquals("a criterion sitting at its threshold contributes zero", 0.0, d.strength, 1e-9)
    }

    @Test
    fun detectorIsDeterministic() {
        val frame = composite(darkFrac = 0.40, brightFrac = 0.55, darkLuma = 40, brightLuma = 200, midLuma = 100)
        assertEquals(BacklitDetector.detect(frame).strength, BacklitDetector.detect(frame).strength, 0.0)
    }

    @Test
    fun emptyFrameIsQuiet() {
        val d = BacklitDetector.detect(Frame(0, 0, IntArray(0), 0L))
        assertEquals(0.0, d.strength, 0.0)
    }

    // --- helpers -------------------------------------------------------------------

    /**
     * A 100x100 frame: the first [darkFrac] of rows filled at [darkLuma], the next
     * [brightFrac] at [brightLuma], the remainder at [midLuma]. Row-quantised, so fractions
     * are exact to 1/100.
     */
    private fun composite(
        darkFrac: Double,
        brightFrac: Double,
        darkLuma: Int,
        brightLuma: Int,
        midLuma: Int,
    ): Frame {
        val w = 100
        val h = 100
        val darkRows = (darkFrac * h).toInt()
        val brightRows = (brightFrac * h).toInt()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val v = when {
                y < darkRows -> darkLuma
                y < darkRows + brightRows -> brightLuma
                else -> midLuma
            }
            val g = gray(v)
            for (x in 0 until w) out[y * w + x] = g
        }
        return Frame(w, h, out, timestampMillis = 0L)
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun smoothstep(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
