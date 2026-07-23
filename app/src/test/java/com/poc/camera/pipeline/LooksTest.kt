package com.poc.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksTest {

    private val lumaR = 0.299f
    private val lumaG = 0.587f
    private val lumaB = 0.114f

    private fun luma(rgb: FloatArray): Float =
        lumaR * rgb[0] + lumaG * rgb[1] + lumaB * rgb[2]

    @Test
    fun neutralIsIdentity() {
        val size = 17
        val neutral = Looks.neutral(size)
        val identity = Lut3d.identity(size)
        assertArrayEquals(identity.data, neutral.data, 0f)
    }

    @Test
    fun cinematicDiffersFromIdentity() {
        val size = 17
        val cinematic = Looks.cinematic(size)
        val identity = Lut3d.identity(size)
        var maxDelta = 0f
        for (i in cinematic.data.indices) {
            val d = kotlin.math.abs(cinematic.data[i] - identity.data[i])
            if (d > maxDelta) maxDelta = d
        }
        assertTrue("cinematic should differ meaningfully from identity", maxDelta > 0.01f)
    }

    @Test
    fun cinematicStaysInRange() {
        val cinematic = Looks.cinematic(17)
        for (v in cinematic.data) {
            assertTrue("value out of [0,1]: $v", v in 0f..1f)
        }
    }

    @Test
    fun cinematicIsDeterministic() {
        val a = Looks.cinematic(17)
        val b = Looks.cinematic(17)
        assertArrayEquals(a.data, b.data, 0f)
    }

    @Test
    fun cinematicPreservesMonotonicLumaOnGrayAxis() {
        val cinematic = Looks.cinematic(17)
        var previous = Float.NEGATIVE_INFINITY
        // Sample the gray ramp finely (not only at lattice points).
        for (step in 0..100) {
            val v = step / 100f
            val out = cinematic.sample(v, v, v)
            val l = luma(out)
            assertTrue(
                "luma not monotonic at gray=$v: previous=$previous current=$l",
                l >= previous - 1e-4f,
            )
            previous = l
        }
    }

    @Test
    fun cinematicPushesShadowsCoolAndHighlightsWarm() {
        val cinematic = Looks.cinematic(17)
        // Deep shadow: blue channel should end up above red (teal lean).
        val shadow = cinematic.sample(0.15f, 0.15f, 0.15f)
        assertTrue("shadows should lean cool (b >= r)", shadow[2] >= shadow[0])
        // Bright highlight: red channel should end up above blue (orange lean).
        val highlight = cinematic.sample(0.85f, 0.85f, 0.85f)
        assertTrue("highlights should lean warm (r >= b)", highlight[0] >= highlight[2])
    }

    // --- Skin-safe cinematic (issue #134): same look properties must hold ------

    @Test
    fun skinSafeCinematicDiffersFromIdentity() {
        val size = 17
        val look = Looks.skinSafeCinematic(size)
        val identity = Lut3d.identity(size)
        var maxDelta = 0f
        for (i in look.data.indices) {
            val d = kotlin.math.abs(look.data[i] - identity.data[i])
            if (d > maxDelta) maxDelta = d
        }
        assertTrue("skin-safe cinematic should differ meaningfully from identity", maxDelta > 0.01f)
    }

    @Test
    fun skinSafeCinematicStaysInRange() {
        val look = Looks.skinSafeCinematic(17)
        for (v in look.data) {
            assertTrue("value out of [0,1]: $v", v in 0f..1f)
        }
    }

    @Test
    fun skinSafeCinematicIsDeterministic() {
        val a = Looks.skinSafeCinematic(17)
        val b = Looks.skinSafeCinematic(17)
        assertArrayEquals(a.data, b.data, 0f)
    }

    @Test
    fun skinSafeCinematicPreservesMonotonicLumaOnGrayAxis() {
        val look = Looks.skinSafeCinematic(17)
        var previous = Float.NEGATIVE_INFINITY
        // Sample the gray ramp finely (not only at lattice points).
        for (step in 0..100) {
            val v = step / 100f
            val out = look.sample(v, v, v)
            val l = luma(out)
            assertTrue(
                "luma not monotonic at gray=$v: previous=$previous current=$l",
                l >= previous - 1e-4f,
            )
            previous = l
        }
    }

    @Test
    fun skinSafeCinematicPushesShadowsCoolAndHighlightsWarm() {
        val look = Looks.skinSafeCinematic(17)
        // The teal-orange character survives the skin protection off skin: gray
        // has no skin chroma, so shadows still lean cool and highlights warm.
        val shadow = look.sample(0.15f, 0.15f, 0.15f)
        assertTrue("shadows should lean cool (b >= r)", shadow[2] >= shadow[0])
        val highlight = look.sample(0.85f, 0.85f, 0.85f)
        assertTrue("highlights should lean warm (r >= b)", highlight[0] >= highlight[2])
    }
}
