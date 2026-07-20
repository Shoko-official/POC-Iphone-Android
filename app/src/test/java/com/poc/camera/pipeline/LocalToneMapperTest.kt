package com.poc.camera.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToneMapperTest {

    private fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun luma(pixel: Int): Double =
        0.299 * ((pixel shr 16) and 0xFF) +
            0.587 * ((pixel shr 8) and 0xFF) +
            0.114 * (pixel and 0xFF)

    /** Mean of a channel over a column band [x0, x1) of a [width]-wide frame. */
    private fun meanLumaBand(frame: Frame, width: Int, height: Int, x0: Int, x1: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until height) {
            for (x in x0 until x1) {
                sum += luma(frame.argb[y * width + x])
                count++
            }
        }
        return sum / count
    }

    @Test
    fun liftsLocalShadowsAndTamesHighlightsWithoutClipping() {
        // Left half a flat deep shadow, right half a flat near-highlight, split by a
        // hard step the guided filter preserves so each side stays flat in its base.
        val width = 64
        val height = 32
        val dark = 30
        val bright = 220
        val argb = IntArray(width * height) { i -> if ((i % width) < width / 2) gray(dark) else gray(bright) }
        val input = Frame(width, height, argb, 0L)

        val output = LocalToneMapper.apply(input) // defaults: baseCompression 0.15, detailGain 1.15

        // Sample interior bands away from the central edge.
        val darkBefore = meanLumaBand(input, width, height, 4, 12)
        val darkAfter = meanLumaBand(output, width, height, 4, 12)
        val brightBefore = meanLumaBand(input, width, height, 52, 60)
        val brightAfter = meanLumaBand(output, width, height, 52, 60)

        assertTrue("dark region should be lifted: $darkBefore -> $darkAfter", darkAfter > darkBefore + 5.0)
        assertTrue("highlight region should be tamed, not lifted: $brightBefore -> $brightAfter", brightAfter < brightBefore)
        // No channel clips to pure white in the highlight region.
        for (y in 0 until height) {
            for (x in 32 until width) {
                assertTrue("highlight must not clip", (output.argb[y * width + x] and 0xFF) < 255)
            }
        }
    }

    @Test
    fun amplifiesDetailByTheGain() {
        // A mid-range gray texture (kept away from 0/255 so nothing clamps) run with
        // pure detail gain (no base compression). Against the shared base, the output
        // detail amplitude should scale by the gain.
        val width = 48
        val height = 48
        val canvas = SyntheticImages.texturedCanvas(width, height, seed = 0x5A5AL)
        // Compress the full-range canvas into [90, 170].
        val argb = IntArray(canvas.size) { gray(90 + (canvas[it] and 0xFF) * 80 / 255) }
        val input = Frame(width, height, argb, 0L)

        val gain = 1.5
        val params = LocalToneParams(radius = 8, baseCompression = 0.0, detailGain = gain)
        val output = LocalToneMapper.apply(input, params)

        val eps = LocalToneParams.DEFAULT_EPS
        val lumaIn = DoubleArray(input.argb.size) { luma(input.argb[it]) }
        val lumaOut = DoubleArray(output.argb.size) { luma(output.argb[it]) }
        val base = GuidedFilter.selfGuided(lumaIn, width, height, 8, eps)

        var detailIn = 0.0
        var detailOut = 0.0
        for (i in lumaIn.indices) {
            detailIn += abs(lumaIn[i] - base[i])
            detailOut += abs(lumaOut[i] - base[i])
        }
        val ratio = detailOut / detailIn
        assertTrue("detail should amplify by ~$gain, measured $ratio", ratio in 1.35..1.65)
    }

    @Test
    fun preservesPerChannelRatios() {
        // A coloured image: local tone maps luma only, applying one ratio to all three
        // channels, so hue proxies (R/G, B/G) survive where nothing clamps.
        val width = 40
        val height = 40
        val canvas = SyntheticImages.texturedCanvas(width, height, seed = 0x1234L)
        val argb = IntArray(canvas.size) { i ->
            val v = canvas[i] and 0xFF
            val r = (60 + v / 3).coerceIn(1, 254)
            val g = (90 + v / 4).coerceIn(1, 254)
            val b = (50 + v / 5).coerceIn(1, 254)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val input = Frame(width, height, argb, 0L)
        val output = LocalToneMapper.apply(input)

        for (i in input.argb.indices) {
            val ip = input.argb[i]
            val op = output.argb[i]
            val ir = (ip shr 16) and 0xFF; val ig = (ip shr 8) and 0xFF; val ib = ip and 0xFF
            val or = (op shr 16) and 0xFF; val og = (op shr 8) and 0xFF; val ob = op and 0xFF
            // Skip pixels that hit the [0,255] clamp, where the ratio is intentionally broken.
            if (or == 0 || or == 255 || og == 0 || og == 255 || ob == 0 || ob == 255) continue
            assertEquals("R/G hue proxy", ir.toDouble() / ig, or.toDouble() / og, 0.06)
            assertEquals("B/G hue proxy", ib.toDouble() / ig, ob.toDouble() / og, 0.06)
        }
    }

    @Test
    fun preservesDimensionsAndAlpha() {
        val input = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(gray(40), gray(200)),
            timestampMillis = 77L,
        )
        val output = LocalToneMapper.apply(input)
        assertEquals(2, output.width)
        assertEquals(1, output.height)
        assertEquals(77L, output.timestampMillis)
        for (pixel in output.argb) assertEquals(0xFF, (pixel ushr 24) and 0xFF)
    }

    @Test
    fun isDeterministic() {
        val width = 32
        val height = 32
        val canvas = SyntheticImages.texturedCanvas(width, height, seed = 0xABCDL)
        val input = Frame(width, height, canvas, 0L)
        val first = LocalToneMapper.apply(input)
        val second = LocalToneMapper.apply(input)
        assertTrue(first.argb.contentEquals(second.argb))
    }
}
