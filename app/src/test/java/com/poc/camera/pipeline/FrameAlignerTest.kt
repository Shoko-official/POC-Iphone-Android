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

    /** [frame] with a bright textured walking-subject-scale block stamped at (x0, y0). */
    private fun withSubject(frame: Frame, x0: Int, y0: Int): Frame {
        val subjectW = 24
        val subjectH = 32
        val texture = SyntheticImages.texturedCanvas(subjectW, subjectH, seed = 0x0B7EC7L)
        val argb = frame.argb.copyOf()
        for (y in 0 until subjectH) {
            for (x in 0 until subjectW) {
                // Remap the texture to a bright 180..255 band, clearly above the canvas.
                val v = 180 + ((texture[y * subjectW + x] and 0xFF) * 75) / 255
                argb[(y0 + y) * frame.width + (x0 + x)] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Frame(frame.width, frame.height, argb, frame.timestampMillis)
    }

    /**
     * Two-population offset field (issue #130): the background -- the majority of the
     * coarsest level's vote tiles -- drifts by a few pixels, while a bright textured
     * subject block jumps tens of pixels between frames. The whole-frame MAD argmin
     * used to follow the subject; the per-tile median vote must recover the
     * background drift, because the subject can only corrupt a minority of tiles.
     * A 128px frame gives the coarsest level a 4x4 vote grid, so the subject
     * (24x32 px, ~2x2 coarse tiles) is a clear minority.
     */
    @Test
    fun mediansOutAWalkingSubjectAndRecoversTheCameraDrift() {
        val big = 128
        val bigCanvas = SyntheticImages.texturedCanvas(big + 2 * margin, big + 2 * margin, seed = 0x5EEDL)
        fun cropBig(sx: Int, sy: Int): Frame =
            SyntheticImages.crop(bigCanvas, big + 2 * margin, margin - sx, margin - sy, big, big)

        val subjectX = 20
        val subjectY = 48
        val drifts = listOf(3 to 2, -4 to 1)
        val subjectSteps = listOf(36, 60)
        val frames = listOf(withSubject(cropBig(0, 0), subjectX, subjectY)) +
            drifts.mapIndexed { i, (sx, sy) ->
                // The subject appears in the frame at its scene position plus the
                // camera shift (the same mapping the background undergoes).
                withSubject(cropBig(sx, sy), subjectX + subjectSteps[i] + sx, subjectY + sy)
            }

        val alignments = FrameAligner().align(frames)

        drifts.forEachIndexed { i, (sx, sy) ->
            val alignment = alignments[i + 1]
            assertEquals("dx for drift ($sx,$sy)", sx, alignment.dx)
            assertEquals("dy for drift ($sx,$sy)", sy, alignment.dy)
            assertTrue("drift ($sx,$sy) should be accepted", alignment.accepted)
        }
    }

    /**
     * Degenerate case: a frame whose coarsest pyramid level is too small to carry the
     * minimum number of complete vote tiles falls back to the whole-frame
     * central-region search and still recovers plain shifts exactly.
     */
    @Test
    fun tinyFramesFallBackToTheWholeFrameSearch() {
        val tiny = 20 // coarsest level 5x5: no complete 8px vote tile
        val tinyMargin = 8
        val canvasTiny = SyntheticImages.texturedCanvas(
            tiny + 2 * tinyMargin, tiny + 2 * tinyMargin, seed = 0x71DEL,
        )
        fun crop(sx: Int, sy: Int): Frame = SyntheticImages.crop(
            canvasTiny, tiny + 2 * tinyMargin, tinyMargin - sx, tinyMargin - sy, tiny, tiny,
        )

        val shifts = listOf(2 to 1, -3 to 2)
        val frames = listOf(crop(0, 0)) + shifts.map { (sx, sy) -> crop(sx, sy) }

        val alignments = FrameAligner().align(frames)

        shifts.forEachIndexed { index, (sx, sy) ->
            val alignment = alignments[index + 1]
            assertEquals("dx for tiny shift ($sx,$sy)", sx, alignment.dx)
            assertEquals("dy for tiny shift ($sx,$sy)", sy, alignment.dy)
        }
    }
}
