package com.poc.camera.pipeline

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LaplacianExposureFusionTest {

    private fun solid(width: Int, height: Int, value: Int): Frame {
        val pixel = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        return Frame(width, height, IntArray(width * height) { pixel }, 0L)
    }

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    @Test
    fun singleFrameIsIdentityWithinRounding() {
        val frame = Frame(48, 48, SyntheticImages.texturedCanvas(48, 48, seed = 0x1L), 0L)
        val fused = LaplacianExposureFusion.fuse(listOf(frame), listOf(0.0))
        for (i in frame.argb.indices) {
            assertTrue(
                "single-frame fusion drifted at $i",
                abs(red(fused.argb[i]) - red(frame.argb[i])) <= 1,
            )
        }
    }

    @Test
    fun identicalFramesReturnThatFrameWithinRounding() {
        val frame = Frame(48, 48, SyntheticImages.texturedCanvas(48, 48, seed = 0x2L), 0L)
        val fused = LaplacianExposureFusion.fuse(listOf(frame, frame), listOf(0.0, 0.0))
        for (i in frame.argb.indices) {
            assertTrue(abs(red(fused.argb[i]) - red(frame.argb[i])) <= 1)
        }
    }

    @Test
    fun fusionFavoursTheWellExposedFrame() {
        val dark = solid(32, 32, 20)
        val bright = solid(32, 32, 128)
        val fused = LaplacianExposureFusion.fuse(listOf(dark, bright), listOf(-2.0, 0.0))
        assertTrue("fused should lean towards 128", red(fused.argb[0]) > (20 + 128) / 2)
    }

    @Test
    fun clippedEverywhereFallsBackToAverageWithoutNaN() {
        val a = solid(16, 16, 255)
        val b = solid(16, 16, 255)
        val fused = LaplacianExposureFusion.fuse(listOf(a, b), listOf(2.0, 2.0))
        assertTrue(fused.argb.all { red(it) == 255 })
    }

    @Test
    fun outputInheritsReferenceDimensionsAndTimestamp() {
        val a = Frame(10, 6, IntArray(60) { 0xFF808080.toInt() }, 1234L)
        val b = Frame(10, 6, IntArray(60) { 0xFF404040.toInt() }, 9999L)
        val fused = LaplacianExposureFusion.fuse(listOf(a, b), listOf(0.0, -2.0))
        assertEquals(10, fused.width)
        assertEquals(6, fused.height)
        assertEquals(1234L, fused.timestampMillis)
    }

    @Test
    fun fusionIsDeterministic() {
        val a = Frame(48, 48, SyntheticImages.texturedCanvas(48, 48, seed = 0x3L), 0L)
        val b = Frame(48, 48, SyntheticImages.texturedCanvas(48, 48, seed = 0x4L), 0L)
        val first = LaplacianExposureFusion.fuse(listOf(a, b), listOf(0.0, 2.0))
        val second = LaplacianExposureFusion.fuse(listOf(a, b), listOf(0.0, 2.0))
        assertEquals(first, second)
    }

    @Test
    fun fusionIsSerialParallelBitIdentical() {
        val a = Frame(65, 49, SyntheticImages.texturedCanvas(65, 49, seed = 0x5L), 0L)
        val b = Frame(65, 49, SyntheticImages.texturedCanvas(65, 49, seed = 0x6L), 0L)
        val serial = LaplacianExposureFusion.fuse(
            listOf(a, b), listOf(-2.0, 2.0), chunkCount = PipelineParallel.SERIAL_CHUNKS,
        )
        val parallel = LaplacianExposureFusion.fuse(
            listOf(a, b), listOf(-2.0, 2.0), chunkCount = PipelineParallel.parallelism,
        )
        assertEquals(serial, parallel)
    }

    @Test
    fun mismatchedFrameAndEvCountsAreRejected() {
        val a = solid(4, 4, 100)
        assertThrows(IllegalArgumentException::class.java) {
            LaplacianExposureFusion.fuse(listOf(a, a), listOf(0.0))
        }
    }

    @Test
    fun emptyInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LaplacianExposureFusion.fuse(emptyList(), emptyList())
        }
    }

    /**
     * The halo test. A bright disk on a dark background is fused from two exposures:
     *
     *  - a DARK capture (EV -2) where the disk is well-exposed (118) but the
     *    background is crushed toward black (5);
     *  - a BRIGHT capture (EV +2) where the background is well-exposed (160) but the
     *    disk is blown out and clipped (255).
     *
     * Ideal fusion picks the disk from the dark frame and the background from the
     * bright frame, giving the sharp-edged truth (disk 118, background 160). The
     * box-blur weight approximation smears the disk's "trust the dark frame"
     * preference into the surrounding background, dragging the ring of pixels just
     * OUTSIDE the disk down toward the dark frame's crushed background -- the classic
     * bright-edge halo. We measure that ring (dilated disk minus disk) as MAE from
     * truth and assert the Laplacian pyramid fusion halos strictly less than the
     * box-blur fusion on identical inputs.
     */
    @Test
    fun laplacianFusionHalosLessThanBoxBlur() {
        val size = 128
        val cx = 64.0
        val cy = 64.0
        // A small, high-contrast disk maximises the box-blur halo: its square weight
        // window over-captures a convex bright region from the surrounding ring, so the
        // ring picks up a strong dark-frame preference. The band is the 4 px ring just
        // outside the disk (dilated disk minus disk).
        val radius = 14.0
        val bandOuter = radius + 4.0

        val diskValue = 118
        val darkBackground = 5 // crushed in the dark capture (at the shadow-clip floor)
        val brightBackground = 160
        val clippedDisk = 255

        fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        fun distance(x: Int, y: Int) = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))

        val darkArgb = IntArray(size * size)
        val brightArgb = IntArray(size * size)
        val truthArgb = IntArray(size * size)
        val band = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val d = distance(x, y)
                val inDisk = d <= radius
                darkArgb[i] = gray(if (inDisk) diskValue else darkBackground)
                brightArgb[i] = gray(if (inDisk) clippedDisk else brightBackground)
                truthArgb[i] = gray(if (inDisk) diskValue else brightBackground)
                band[i] = !inDisk && d <= bandOuter
            }
        }
        val dark = Frame(size, size, darkArgb, 0L)
        val bright = Frame(size, size, brightArgb, 0L)
        val truth = Frame(size, size, truthArgb, 0L)
        val evs = listOf(-2.0, 2.0)

        val boxBlur = ExposureFusion.fuse(listOf(dark, bright), evs)
        val laplacian = LaplacianExposureFusion.fuse(listOf(dark, bright), evs)

        val boxBlurBandMae = bandMae(boxBlur, truth, band)
        val laplacianBandMae = bandMae(laplacian, truth, band)

        println("halo band MAE -- box-blur=$boxBlurBandMae laplacian=$laplacianBandMae")
        assertTrue(
            "Laplacian band MAE $laplacianBandMae must beat box-blur $boxBlurBandMae",
            laplacianBandMae < boxBlurBandMae,
        )
    }

    private fun bandMae(fused: Frame, truth: Frame, band: BooleanArray): Double {
        var sum = 0.0
        var count = 0
        for (i in band.indices) {
            if (!band[i]) continue
            sum += abs(red(fused.argb[i]) - red(truth.argb[i])).toDouble()
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }
}
