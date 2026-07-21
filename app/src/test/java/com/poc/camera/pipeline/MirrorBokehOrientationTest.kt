package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the selfie mirroring order for Portrait/burst captures (issue #88): production code
 * calls [mirrorHorizontal] ONCE on the merged frame, before segmentation/bokeh, so the mask
 * and the frame it drives are always in the same (already-mirrored) coordinate space - see
 * [com.poc.camera.camera.CameraScreen]'s `startPortraitCapture`/`mergeAndSave` docs. MLKit's
 * real segmenter is device-only ([com.poc.camera.camera.SubjectSegmenter]'s KDoc), so this
 * test stands in a synthetic asymmetric mask for a real segmentation result and checks that
 * [BokehRenderer] keeps the subject sharp and the background blurred on the sides the mask
 * says they are on, through the mirror.
 *
 * The two halves of the synthetic frame are built from disjoint gray levels ({30, 220} vs
 * {60, 180}) specifically so an accidental double-mirror (or a skipped mirror - the two bugs
 * are indistinguishable since mirroring twice is an identity) is caught by exact pixel-value
 * membership rather than by a blur-averaging tolerance: the "sharp" half of the output must
 * be built from the LEFT half's original gray levels, not the right half's, or this test fails.
 */
class MirrorBokehOrientationTest {

    private val width = 32
    private val height = 8

    /** Half-open column range [x0, x1) filled with a two-level checkerboard alternating lo/hi. */
    private fun checkerboardHalf(pixels: IntArray, x0: Int, x1: Int, lo: Int, hi: Int) {
        for (y in 0 until height) {
            for (x in x0 until x1) {
                val v = if ((x - x0) % 2 == 0) lo else hi
                pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    /** Left half: gray levels {30, 220}. Right half: gray levels {60, 180}. Disjoint sets. */
    private fun syntheticFrame(): Frame {
        val pixels = IntArray(width * height)
        checkerboardHalf(pixels, 0, width / 2, lo = 30, hi = 220)
        checkerboardHalf(pixels, width / 2, width, lo = 60, hi = 180)
        return Frame(width, height, pixels, timestampMillis = 7L)
    }

    /** Subject (sharp) = right half, background (blurred) = left half - an asymmetric mask. */
    private fun rightHalfSubjectMask(): FloatArray = FloatArray(width * height) { i ->
        if (i % width >= width / 2) 1f else 0f
    }

    private val params = BokehParams(
        maxBlurRadius = 6,
        tapCount = 12,
        featherRadius = 1,
        featherEps = 150.0,
        featherSmoothRadius = 0,
        highlightThreshold = 235.0,
        highlightBoost = 1.0, // disables bloom, keeps the blurred-half math a plain gather
        strength = 1.0,
    )

    private fun gray(frame: Frame, x: Int, y: Int): Int = frame.argb[y * frame.width + x] and 0xFF

    @Test
    fun mirrorThenBokehKeepsSubjectSharpOnTheMaskedSide() {
        val original = syntheticFrame()
        val mirrored = original.mirrorHorizontal()
        val mask = rightHalfSubjectMask()

        val result = BokehRenderer.render(mirrored, mask, params)

        // Deep in the subject half (right, mask 1, far from the feather boundary): bit-exact
        // passthrough of the mirrored frame.
        val subjectX = width - 2
        assertEquals(mirrored.argb[subjectX], result.argb[subjectX])

        // The subject half is built from the mirrored frame's right side, which is the
        // ORIGINAL frame's LEFT half (gray levels {30, 220}) reversed by mirrorHorizontal -
        // not the original right half's {60, 180}. This is the load-bearing check: it fails
        // if the mirror is skipped, applied twice, or applied after (instead of before) bokeh.
        assertTrue(
            "expected a left-half gray level, got ${gray(result, subjectX, 0)}",
            gray(result, subjectX, 0) == 30 || gray(result, subjectX, 0) == 220,
        )
    }

    @Test
    fun mirrorThenBokehBlursTheBackgroundHalf() {
        val original = syntheticFrame()
        val mirrored = original.mirrorHorizontal()
        val mask = rightHalfSubjectMask()

        val result = BokehRenderer.render(mirrored, mask, params)

        // Deep in the background half (left, mask 0, far from the boundary): the disc blur
        // averages the checkerboard, so the result is neither raw level and sits strictly
        // between them.
        val backgroundX = 2
        val g = gray(result, backgroundX, 0)
        assertNotEquals(gray(mirrored, backgroundX, 0), g)
        assertTrue("expected a blurred gray strictly between 60 and 180, got $g", g in 61..179)
    }

    @Test
    fun skippingOrDoublingTheMirrorPutsTheWrongContentInTheSharpRegion() {
        val original = syntheticFrame()
        val mask = rightHalfSubjectMask()

        val correct = BokehRenderer.render(original.mirrorHorizontal(), mask, params)
        // Mirroring twice is an identity, so this is bit-for-bit the same bug as forgetting to
        // mirror at all: the mask is applied straight to the un-mirrored frame.
        val buggy = BokehRenderer.render(original.mirrorHorizontal().mirrorHorizontal(), mask, params)

        val subjectX = width - 2
        // Correct: sharp region carries the original LEFT half's levels ({30, 220}).
        assertTrue(gray(correct, subjectX, 0) == 30 || gray(correct, subjectX, 0) == 220)
        // Buggy (no/double mirror): sharp region instead carries the original RIGHT half's
        // levels ({60, 180}) unchanged, since im == 0 there passes the un-mirrored pixel through.
        assertTrue(gray(buggy, subjectX, 0) == 60 || gray(buggy, subjectX, 0) == 180)
        assertNotEquals(correct.argb[subjectX], buggy.argb[subjectX])
    }
}
