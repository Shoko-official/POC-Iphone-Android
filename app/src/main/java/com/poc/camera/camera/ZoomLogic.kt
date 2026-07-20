package com.poc.camera.camera

import java.util.Locale

/**
 * Pinch-to-zoom math and chip presentation, kept free of Android/CameraX classes so it can
 * be unit tested without Robolectric. The UI layer (CameraScreen) owns the desired zoom
 * ratio as Compose state, feeds it and the live [androidx.camera.core.ZoomState] bounds
 * through [computeNewRatio] on every pinch delta and on reset, and asks [shouldShowChip] /
 * [formatLabel] how to present the result.
 *
 * Device-gap disclosure: [computeNewRatio] never invents a ratio the device can't actually
 * reach - it always clamps into the caller-supplied [min, max]. A device whose true minimum
 * is above 1.0 (no true wide-angle 1x) or whose maximum is below 2.0 (no meaningful zoom
 * range) is never lied to about being at "1.0x"; [formatLabel] always reflects the clamped,
 * actually-applied ratio, so the chip itself is the disclosure - it shows "2.0x" as the
 * floor, not the reset target the caller asked for.
 */
object ZoomLogic {

    /**
     * Applies a pinch's multiplicative [gestureFactor] (CameraX/Compose's `calculateZoom()`,
     * >1 zooming in, <1 zooming out) to [current] and clamps into [min, max]. Also doubles as
     * the reset computation: `computeNewRatio(current = 1f, gestureFactor = 1f, min, max)`
     * clamps the ideal 1.0x reset target into the device's real range.
     *
     * Guards every input against non-finite values (NaN/Infinity) and an inverted range, so a
     * transient bad reading from CameraX's ZoomState LiveData can't propagate into a NaN
     * zoom ratio or a crashing [androidx.camera.core.CameraControl.setZoomRatio] call.
     */
    fun computeNewRatio(current: Float, gestureFactor: Float, min: Float, max: Float): Float {
        val safeMin = if (min.isFinite()) min else 1f
        val safeMax = if (max.isFinite() && max >= safeMin) max else safeMin
        val safeCurrent = if (current.isFinite()) current.coerceIn(safeMin, safeMax) else safeMin
        val safeFactor = if (gestureFactor.isFinite() && gestureFactor > 0f) gestureFactor else 1f
        return (safeCurrent * safeFactor).coerceIn(safeMin, safeMax)
    }

    /** "2.3x" - always one decimal, always dot-separated regardless of device locale. */
    fun formatLabel(ratio: Float): String {
        val safe = if (ratio.isFinite()) ratio else 1f
        return String.format(Locale.US, "%.1fx", safe)
    }

    /**
     * The chip is a transient zoom indicator, not a permanent control: visible while actively
     * pinching (so the user gets feedback mid-gesture even at exactly 1.0x), or whenever the
     * settled ratio would display as something other than "1.0x". Compares on the same
     * one-decimal rounding as [formatLabel] rather than exact equality, so float noise from
     * repeated multiplication (e.g. 1.0000001f) never leaves a "1.0x" chip stuck on screen.
     */
    fun shouldShowChip(ratio: Float, isPinching: Boolean): Boolean {
        if (isPinching) return true
        if (!ratio.isFinite()) return false
        return Math.round(ratio * 10f) != 10
    }
}
