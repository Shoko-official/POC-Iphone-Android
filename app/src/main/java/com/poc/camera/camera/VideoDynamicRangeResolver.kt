package com.poc.camera.camera

/**
 * Mirrors the encoding/bit-depth pair androidx.camera.core.DynamicRange exposes, kept as
 * plain ints so [VideoDynamicRangeResolver] stays free of CameraX classes and can be unit
 * tested without Robolectric; [VideoDynamicRangeCapabilities] is the thin layer that maps
 * a bound camera's reported ranges into this shape.
 */
data class SupportedRange(val encoding: Int, val bitDepth: Int) {
    companion object {
        // Matches androidx.camera.core.DynamicRange.ENCODING_HLG / BIT_DEPTH_10_BIT.
        const val ENCODING_HLG = 3
        const val BIT_DEPTH_10_BIT = 10
        val HLG_10_BIT = SupportedRange(ENCODING_HLG, BIT_DEPTH_10_BIT)
    }
}

enum class VideoDynamicRangeDecision {
    UseHlg10,
    UseSdr,
}

/**
 * Decides whether Video/Cinematic recording should request 10-bit HLG output, given what
 * the device's Recorder reports as supported and whether the user has HDR video enabled in
 * settings. Kept free of Android/CameraX classes so it can be unit tested without
 * Robolectric; [VideoDynamicRangeCapabilities] supplies the CameraX data.
 */
object VideoDynamicRangeResolver {

    fun resolve(
        supportedRanges: Set<SupportedRange>,
        hdrEnabled: Boolean,
    ): VideoDynamicRangeDecision {
        if (!hdrEnabled) return VideoDynamicRangeDecision.UseSdr
        return if (SupportedRange.HLG_10_BIT in supportedRanges) {
            VideoDynamicRangeDecision.UseHlg10
        } else {
            VideoDynamicRangeDecision.UseSdr
        }
    }
}
