package com.poc.camera.pipeline

import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

class RobustFrameMergerTest {

    private val tileSize = 32

    /** A zero (integer, no motion) tile-offset grid for a width x height frame. */
    private fun zeroOffsets(width: Int, height: Int): TileAligner.TileOffsets {
        val cols = (width + tileSize - 1) / tileSize
        val rows = (height + tileSize - 1) / tileSize
        return TileAligner.TileOffsets(cols, rows, tileSize, DoubleArray(cols * rows), DoubleArray(cols * rows))
    }

    private fun Frame.channel(x: Int, y: Int, shift: Int): Int =
        (argb[y * width + x] shr shift) and 0xFF

    /**
     * Naive per-channel mean (integer, rounded) of the accepted frames at zero offset:
     * the un-gated averaging reference the robust merge is compared against.
     */
    private fun plainMean(frames: List<Frame>, accepted: List<Boolean>): Frame {
        val reference = frames.first()
        val out = IntArray(reference.argb.size)
        for (i in out.indices) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (index in frames.indices) {
                if (!accepted[index]) continue
                val pixel = frames[index].argb[i]
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
                count++
            }
            val half = count / 2
            out[i] = (0xFF shl 24) or
                (((sumR + half) / count) shl 16) or
                (((sumG + half) / count) shl 8) or
                ((sumB + half) / count)
        }
        return Frame(reference.width, reference.height, out, reference.timestampMillis)
    }

    @Test
    fun withUnitWeightsAndIntegerOffsetsReproducesAveraging() {
        val width = 64
        val height = 64
        val base = SyntheticImages.texturedCanvas(width, height, seed = 0xB0BAL)
        val frames = (0 until 5).map { i ->
            SyntheticImages.noisyVariant(base, width, height, seed = 10L + i, amplitude = 25)
        }
        val accepted = List(frames.size) { true }
        val offsets = frames.indices.map { if (it == 0) null else zeroOffsets(width, height) }

        // A permissive rejector keeps every weight at 1, so the robust merge reduces
        // to the plain per-channel mean.
        val permissive = GhostRejector(loScale = 1e6, hiScale = 1e6 + 1.0)
        val robust = RobustFrameMerger.merge(frames, accepted, offsets, permissive)
        val plain = plainMean(frames, accepted)

        for (y in 0 until height) {
            for (x in 0 until width) {
                for (shift in listOf(16, 8, 0)) {
                    val diff = abs(robust.channel(x, y, shift) - plain.channel(x, y, shift))
                    assertTrue("channel diff at ($x,$y) shift $shift was $diff", diff <= 1)
                }
            }
        }
    }

    @Test
    fun keepsReferenceValueInAGhostedRegion() {
        val width = 48
        val height = 48
        val gray = 100
        val bright = 220
        fun constant(color: Int) = Frame(
            width, height,
            IntArray(width * height) { (0xFF shl 24) or (color shl 16) or (color shl 8) or color },
            timestampMillis = 0L,
        )

        val reference = constant(gray)
        val clean1 = constant(gray)
        val clean2 = constant(gray)
        // One frame has a bright block moved into the top-left corner: local motion.
        val ghostBlock = 12
        val ghosted = Frame(
            width, height,
            IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val v = if (x < ghostBlock && y < ghostBlock) bright else gray
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            },
            timestampMillis = 0L,
        )

        val frames = listOf(reference, clean1, clean2, ghosted)
        val accepted = List(frames.size) { true }
        val offsets = frames.indices.map { if (it == 0) null else zeroOffsets(width, height) }

        val robust = RobustFrameMerger.merge(frames, accepted, offsets)
        val naive = plainMean(frames, accepted)

        // In the ghost region the robust merge should reject the moved block and hold
        // the reference value; the naive average is pulled toward the bright block.
        var robustError = 0.0
        var naiveError = 0.0
        var count = 0
        for (y in 0 until ghostBlock) {
            for (x in 0 until ghostBlock) {
                robustError += abs(robust.channel(x, y, 16) - gray)
                naiveError += abs(naive.channel(x, y, 16) - gray)
                count++
            }
        }
        robustError /= count
        naiveError /= count

        assertTrue("robust ghost-region error $robustError must be near reference", robustError < 1.0)
        assertTrue(
            "robust error $robustError must be far below naive average error $naiveError",
            robustError < naiveError / 4.0,
        )
    }
}
