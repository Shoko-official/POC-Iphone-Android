package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAlignerTest {

    private val margin = 16
    private val size = 64
    private val canvasSize = size + 2 * margin

    private val canvas = SyntheticImages.texturedCanvas(canvasSize, canvasSize, seed = 0x5EEDL)

    /** Reference crop taken from the canvas centre. */
    private fun reference(): Frame =
        SyntheticImages.crop(canvas, canvasSize, margin, margin, size, size)

    /**
     * A crop displaced so that the aligner should recover offset (sx, sy): the
     * frame is sampled starting at (margin - sx, margin - sy).
     */
    private fun shifted(sx: Int, sy: Int): Frame =
        SyntheticImages.crop(canvas, canvasSize, margin - sx, margin - sy, size, size)

    @Test
    fun recoversKnownIntegerShiftsExactly() {
        val shifts = listOf(0 to 0, 3 to -2, 5 to 7, -6 to 4, 8 to -8)
        val frames = listOf(reference()) + shifts.map { (sx, sy) -> shifted(sx, sy) }

        val alignments = FrameAligner().align(frames)

        assertEquals(0, alignments[0].dx)
        assertEquals(0, alignments[0].dy)
        shifts.forEachIndexed { index, (sx, sy) ->
            val alignment = alignments[index + 1]
            assertEquals("dx for shift ($sx,$sy)", sx, alignment.dx)
            assertEquals("dy for shift ($sx,$sy)", sy, alignment.dy)
            assertTrue("shift ($sx,$sy) should be accepted", alignment.accepted)
        }
    }

    @Test
    fun rejectsUnrelatedNoiseFrame() {
        val frames = listOf(
            reference(),
            shifted(2, 1),
            shifted(-3, 2),
            shifted(4, -1),
            SyntheticImages.noiseFrame(size, size, seed = 0xABCDEFL),
        )

        val alignments = FrameAligner().align(frames)

        assertTrue(alignments[0].accepted)
        assertTrue(alignments[1].accepted)
        assertTrue(alignments[2].accepted)
        assertTrue(alignments[3].accepted)
        assertFalse("unrelated noise frame must be rejected", alignments[4].accepted)
        // The rejected frame's MAD is far above the aligned frames' near-zero MAD.
        assertTrue(alignments[4].meanAbsDiff > alignments[1].meanAbsDiff * 4)
    }
}
