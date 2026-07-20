package com.poc.camera.pipeline

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromaDenoiserTest {

    private fun luma(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) +
            0.587 * ((pixel shr 8) and 0xFF) +
            0.114 * (pixel and 0xFF)

    private fun cr(pixel: Int): Double = ((pixel shr 16) and 0xFF) - luma(pixel)
    private fun cb(pixel: Int): Double = (pixel and 0xFF) - luma(pixel)

    /** Integer luma matching [Luminance.extract] (Rec. 601, rounded fixed point). */
    private fun intLuma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (299 * r + 587 * g + 114 * b + 500) / 1000
    }

    private fun variance(values: List<Double>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    /** A uniform saturated patch with deterministic per-channel (chroma) noise. */
    private fun noisySaturatedPatch(width: Int, height: Int, r: Int, g: Int, b: Int, amp: Int): Frame {
        var state = 0xC0FFEEL
        fun nextDelta(): Int {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((state ushr 16) % (2L * amp + 1L)).toInt() - amp
        }
        val argb = IntArray(width * height) {
            val nr = (r + nextDelta()).coerceIn(0, 255)
            val ng = (g + nextDelta()).coerceIn(0, 255)
            val nb = (b + nextDelta()).coerceIn(0, 255)
            (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return Frame(width, height, argb, 0L)
    }

    @Test
    fun flattensChromaNoiseInUniformPatch() {
        val w = 64
        val h = 64
        val input = noisySaturatedPatch(w, h, r = 200, g = 60, b = 80, amp = 20)
        val output = ChromaDenoiser.apply(input, strength = 1.0, radius = 8)

        // Sample the interior (away from the clamped border) and compare chroma spread.
        val inCr = ArrayList<Double>(); val inCb = ArrayList<Double>()
        val outCr = ArrayList<Double>(); val outCb = ArrayList<Double>()
        for (y in 12 until h - 12) for (x in 12 until w - 12) {
            inCr.add(cr(input.argb[y * w + x])); inCb.add(cb(input.argb[y * w + x]))
            outCr.add(cr(output.argb[y * w + x])); outCb.add(cb(output.argb[y * w + x]))
        }
        val crRatio = variance(outCr) / variance(inCr)
        val cbRatio = variance(outCb) / variance(inCb)
        assertTrue("Cr variance should drop hard, ratio $crRatio", crRatio < 0.35)
        assertTrue("Cb variance should drop hard, ratio $cbRatio", cbRatio < 0.35)
    }

    @Test
    fun preservesSaturationInUniformPatch() {
        // Guided filter averages chroma noise around the true value; it must NOT shrink
        // the patch's saturation (mean chroma magnitude) more than a few percent.
        val w = 64
        val h = 64
        val input = noisySaturatedPatch(w, h, r = 210, g = 40, b = 90, amp = 18)
        val output = ChromaDenoiser.apply(input, strength = 1.0, radius = 8)

        var inMag = 0.0; var outMag = 0.0; var count = 0
        for (y in 12 until h - 12) for (x in 12 until w - 12) {
            val ip = input.argb[y * w + x]; val op = output.argb[y * w + x]
            inMag += sqrt(cr(ip) * cr(ip) + cb(ip) * cb(ip))
            outMag += sqrt(cr(op) * cr(op) + cb(op) * cb(op))
            count++
        }
        inMag /= count; outMag /= count
        // Mean magnitude of noisy chroma is slightly inflated by the noise, so the
        // denoised mean is expected to be a touch lower; require it within 6%.
        assertTrue("saturation must be preserved: $inMag -> $outMag", outMag >= inMag * 0.94)
    }

    @Test
    fun leavesLumaEssentiallyUntouched() {
        // Luma is preserved exactly in the continuous opponent domain; the only drift is
        // the integer RGB reconstruction rounding, bounded to <= 1 code value per pixel.
        val input = noisySaturatedPatch(48, 48, r = 180, g = 90, b = 60, amp = 22)
        val output = ChromaDenoiser.apply(input, strength = 0.7, radius = 4)
        var maxDev = 0
        for (i in input.argb.indices) {
            maxDev = maxOf(maxDev, abs(intLuma(input.argb[i]) - intLuma(output.argb[i])))
        }
        assertTrue("integer luma must stay within 1 code value, max deviation $maxDev", maxDev <= 1)
    }

    @Test
    fun preservesColouredEdgeSharpness() {
        // Two saturated complementary halves split by a hard edge with a co-occurring
        // luma step (red | cyan). The denoiser must keep the colour transition sharp.
        val w = 96
        val h = 32
        val half = w / 2
        val red = (0xFF shl 24) or (220 shl 16) or (30 shl 8) or 30
        val cyan = (0xFF shl 24) or (30 shl 16) or (200 shl 8) or 210
        val argb = IntArray(w * h) { if ((it % w) < half) red else cyan }
        val input = Frame(w, h, argb, 0L)

        val output = ChromaDenoiser.apply(input, strength = 1.0, radius = 8)

        val y = h / 2
        val cleanStep = abs(cr(argb[y * w + (half - 6)]) - cr(argb[y * w + (half + 6)]))
        val outStep = abs(cr(output.argb[y * w + (half - 6)]) - cr(output.argb[y * w + (half + 6)]))
        assertTrue(
            "coloured edge must stay sharp: kept ${outStep / cleanStep} of the Cr step",
            outStep >= 0.9 * cleanStep,
        )
    }

    @Test
    fun strengthZeroIsANoOpApartFromAlpha() {
        val input = noisySaturatedPatch(16, 16, r = 150, g = 60, b = 200, amp = 15)
        val output = ChromaDenoiser.apply(input, strength = 0.0)
        for (i in input.argb.indices) {
            assertEquals(input.argb[i] and 0x00FFFFFF, output.argb[i] and 0x00FFFFFF)
            assertEquals(0xFF, (output.argb[i] ushr 24) and 0xFF)
        }
    }

    @Test
    fun preservesDimensionsAndForcesOpaque() {
        val input = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(
                (0x80 shl 24) or (200 shl 16) or (40 shl 8) or 60,
                (0x10 shl 24) or (30 shl 16) or (200 shl 8) or 210,
            ),
            timestampMillis = 42L,
        )
        val output = ChromaDenoiser.apply(input, strength = 0.6)
        assertEquals(2, output.width)
        assertEquals(1, output.height)
        assertEquals(42L, output.timestampMillis)
        for (pixel in output.argb) assertEquals(0xFF, (pixel ushr 24) and 0xFF)
    }

    @Test
    fun isDeterministic() {
        val input = noisySaturatedPatch(40, 40, r = 190, g = 70, b = 120, amp = 20)
        val first = ChromaDenoiser.apply(input, strength = 0.6, radius = 4)
        val second = ChromaDenoiser.apply(input, strength = 0.6, radius = 4)
        assertTrue(first.argb.contentEquals(second.argb))
    }

    @Test
    fun rejectsStrengthOutOfRange() {
        val input = noisySaturatedPatch(8, 8, r = 100, g = 100, b = 100, amp = 5)
        assertThrows(IllegalArgumentException::class.java) { ChromaDenoiser.apply(input, strength = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { ChromaDenoiser.apply(input, strength = -0.1) }
    }
}
