package com.poc.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneCurveTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun neutralParametersAreIdentityWithinOne() {
        val lut = ToneCurve(shadowsLift = 0.0, highlightRolloff = 0.0).lookupTable
        for (i in 0..255) {
            assertTrue(
                "neutral curve deviates at $i: ${lut[i]}",
                abs(lut[i] - i) <= 1,
            )
        }
    }

    @Test
    fun lookupTableIsMonotonicNonDecreasing() {
        // Check a spread of parameter combinations, including the extremes.
        val combos = listOf(
            0.0 to 0.0,
            1.0 to 0.0,
            0.0 to 1.0,
            1.0 to 1.0,
            0.5 to 0.3,
        )
        for ((lift, rolloff) in combos) {
            val lut = ToneCurve(lift, rolloff).lookupTable
            for (i in 1..255) {
                assertTrue(
                    "non-monotonic at $i for ($lift, $rolloff): ${lut[i - 1]} -> ${lut[i]}",
                    lut[i] >= lut[i - 1],
                )
            }
        }
    }

    @Test
    fun endpointsArePreserved() {
        val lut = ToneCurve(shadowsLift = 1.0, highlightRolloff = 1.0).lookupTable
        assertTrue("black endpoint drifted: ${lut[0]}", lut[0] <= 1)
        assertTrue("white endpoint drifted: ${lut[255]}", lut[255] >= 254)
    }

    @Test
    fun shadowsLiftRaisesDarkValues() {
        val neutral = ToneCurve(shadowsLift = 0.0, highlightRolloff = 0.0).lookupTable
        val lifted = ToneCurve(shadowsLift = 0.6, highlightRolloff = 0.0).lookupTable
        // A dark sample must be brighter once shadows are lifted.
        val dark = 48
        assertTrue(
            "expected lift at $dark: neutral=${neutral[dark]} lifted=${lifted[dark]}",
            lifted[dark] > neutral[dark],
        )
    }

    @Test
    fun highlightRolloffCompressesHighlights() {
        val neutral = ToneCurve(shadowsLift = 0.0, highlightRolloff = 0.0).lookupTable
        val rolled = ToneCurve(shadowsLift = 0.0, highlightRolloff = 0.6).lookupTable
        // A highlight sample must be darker once the highlights roll off.
        val highlight = 204
        assertTrue(
            "expected rolloff at $highlight: neutral=${neutral[highlight]} rolled=${rolled[highlight]}",
            rolled[highlight] < neutral[highlight],
        )
        // The shoulder is highlight-weighted: any effect on shadows is far
        // smaller than the compression applied to highlights.
        val shadow = 48
        val shadowDrop = neutral[shadow] - rolled[shadow]
        val highlightDrop = neutral[highlight] - rolled[highlight]
        assertTrue(
            "rolloff should hit highlights harder than shadows: shadow=$shadowDrop highlight=$highlightDrop",
            highlightDrop > shadowDrop,
        )
    }

    @Test
    fun applyMapsEveryChannelThroughTable() {
        val curve = ToneCurve(shadowsLift = 0.5, highlightRolloff = 0.5)
        val lut = curve.lookupTable
        val frame = Frame(
            width = 2,
            height = 1,
            argb = intArrayOf(argb(48, 128, 204), argb(0, 255, 90)),
            timestampMillis = 7L,
        )

        val out = curve.apply(frame)

        val p0 = out.argb[0]
        assertEquals(lut[48], (p0 shr 16) and 0xFF)
        assertEquals(lut[128], (p0 shr 8) and 0xFF)
        assertEquals(lut[204], p0 and 0xFF)
        assertEquals(7L, out.timestampMillis)
    }
}
