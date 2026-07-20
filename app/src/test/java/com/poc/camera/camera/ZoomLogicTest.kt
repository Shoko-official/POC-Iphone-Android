package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLogicTest {

    // -- computeNewRatio -----------------------------------------------------------------

    @Test
    fun `pinching in multiplies the current ratio by the gesture factor`() {
        val next = ZoomLogic.computeNewRatio(current = 2f, gestureFactor = 1.5f, min = 1f, max = 10f)

        assertEquals(3f, next, 0.0001f)
    }

    @Test
    fun `pinching out multiplies the current ratio down`() {
        val next = ZoomLogic.computeNewRatio(current = 4f, gestureFactor = 0.5f, min = 1f, max = 10f)

        assertEquals(2f, next, 0.0001f)
    }

    @Test
    fun `result is clamped to the device maximum`() {
        val next = ZoomLogic.computeNewRatio(current = 8f, gestureFactor = 3f, min = 1f, max = 10f)

        assertEquals(10f, next, 0.0001f)
    }

    @Test
    fun `result is clamped to the device minimum`() {
        val next = ZoomLogic.computeNewRatio(current = 1.2f, gestureFactor = 0.1f, min = 1f, max = 10f)

        assertEquals(1f, next, 0.0001f)
    }

    @Test
    fun `reset target of 1x clamps to a device whose minimum is above 1x`() {
        // e.g. a phone whose default logical camera has no true 1x wide sensor.
        val next = ZoomLogic.computeNewRatio(current = 1f, gestureFactor = 1f, min = 2f, max = 10f)

        assertEquals(2f, next, 0.0001f)
    }

    @Test
    fun `a device whose maximum is below 2x still allows a small zoom in`() {
        val next = ZoomLogic.computeNewRatio(current = 1f, gestureFactor = 1.3f, min = 1f, max = 1.5f)

        assertEquals(1.3f, next, 0.0001f)
    }

    @Test
    fun `a device whose maximum is below 2x clamps a large pinch to its own ceiling`() {
        val next = ZoomLogic.computeNewRatio(current = 1f, gestureFactor = 5f, min = 1f, max = 1.5f)

        assertEquals(1.5f, next, 0.0001f)
    }

    @Test
    fun `a fixed-zoom device with equal min and max always resolves to that ratio`() {
        val next = ZoomLogic.computeNewRatio(current = 1f, gestureFactor = 2f, min = 1f, max = 1f)

        assertEquals(1f, next, 0.0001f)
    }

    @Test
    fun `NaN current falls back to the device minimum before applying the gesture`() {
        val next = ZoomLogic.computeNewRatio(current = Float.NaN, gestureFactor = 1f, min = 2f, max = 10f)

        assertEquals(2f, next, 0.0001f)
    }

    @Test
    fun `NaN gesture factor is treated as a no-op multiplier`() {
        val next = ZoomLogic.computeNewRatio(current = 3f, gestureFactor = Float.NaN, min = 1f, max = 10f)

        assertEquals(3f, next, 0.0001f)
    }

    @Test
    fun `zero or negative gesture factor is treated as a no-op multiplier`() {
        val next = ZoomLogic.computeNewRatio(current = 3f, gestureFactor = 0f, min = 1f, max = 10f)

        assertEquals(3f, next, 0.0001f)
    }

    @Test
    fun `infinite current is treated as invalid and falls back to the minimum`() {
        val next = ZoomLogic.computeNewRatio(current = Float.POSITIVE_INFINITY, gestureFactor = 1f, min = 1f, max = 10f)

        assertEquals(1f, next, 0.0001f)
    }

    @Test
    fun `NaN bounds fall back to a safe 1x range`() {
        val next = ZoomLogic.computeNewRatio(current = 5f, gestureFactor = 1f, min = Float.NaN, max = Float.NaN)

        assertEquals(1f, next, 0.0001f)
    }

    @Test
    fun `an inverted range falls back to treating the minimum as the only valid ratio`() {
        val next = ZoomLogic.computeNewRatio(current = 5f, gestureFactor = 1f, min = 3f, max = 2f)

        assertEquals(3f, next, 0.0001f)
    }

    // -- formatLabel ----------------------------------------------------------------------

    @Test
    fun `formats a whole ratio with one decimal`() {
        assertEquals("1.0x", ZoomLogic.formatLabel(1f))
    }

    @Test
    fun `formats a fractional ratio rounded to one decimal`() {
        assertEquals("2.3x", ZoomLogic.formatLabel(2.34f))
    }

    @Test
    fun `formats a non-finite ratio as the neutral 1x label`() {
        assertEquals("1.0x", ZoomLogic.formatLabel(Float.NaN))
    }

    // -- shouldShowChip ---------------------------------------------------------------------

    @Test
    fun `chip is hidden at exactly 1x while idle`() {
        assertFalse(ZoomLogic.shouldShowChip(ratio = 1f, isPinching = false))
    }

    @Test
    fun `chip is hidden for a ratio that rounds to 1x despite float noise`() {
        assertFalse(ZoomLogic.shouldShowChip(ratio = 1.0000001f, isPinching = false))
    }

    @Test
    fun `chip is shown once zoomed in past 1x`() {
        assertTrue(ZoomLogic.shouldShowChip(ratio = 1.2f, isPinching = false))
    }

    @Test
    fun `chip is shown mid-pinch even at exactly 1x`() {
        assertTrue(ZoomLogic.shouldShowChip(ratio = 1f, isPinching = true))
    }

    @Test
    fun `chip is hidden for a non-finite ratio`() {
        assertFalse(ZoomLogic.shouldShowChip(ratio = Float.NaN, isPinching = false))
    }
}
