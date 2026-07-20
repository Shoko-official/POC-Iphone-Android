package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDynamicRangeResolverTest {

    @Test
    fun usesHlg10WhenSupportedAndEnabled() {
        val result = VideoDynamicRangeResolver.resolve(
            supportedRanges = setOf(SupportedRange(encoding = 1, bitDepth = 8), SupportedRange.HLG_10_BIT),
            hdrEnabled = true,
        )

        assertEquals(VideoDynamicRangeDecision.UseHlg10, result)
    }

    @Test
    fun fallsBackToSdrWhenHlg10IsNotSupported() {
        val result = VideoDynamicRangeResolver.resolve(
            supportedRanges = setOf(SupportedRange(encoding = 1, bitDepth = 8)),
            hdrEnabled = true,
        )

        assertEquals(VideoDynamicRangeDecision.UseSdr, result)
    }

    @Test
    fun fallsBackToSdrWhenDisabledEvenIfSupported() {
        val result = VideoDynamicRangeResolver.resolve(
            supportedRanges = setOf(SupportedRange.HLG_10_BIT),
            hdrEnabled = false,
        )

        assertEquals(VideoDynamicRangeDecision.UseSdr, result)
    }

    @Test
    fun fallsBackToSdrWhenSupportedRangesAreEmpty() {
        val result = VideoDynamicRangeResolver.resolve(
            supportedRanges = emptySet(),
            hdrEnabled = true,
        )

        assertEquals(VideoDynamicRangeDecision.UseSdr, result)
    }

    @Test
    fun ignoresOtherTenBitEncodingsThatAreNotHlg() {
        // e.g. HDR10 (encoding 4) at 10-bit is not HLG10; only the exact HLG/10-bit pair counts.
        val result = VideoDynamicRangeResolver.resolve(
            supportedRanges = setOf(SupportedRange(encoding = 4, bitDepth = 10)),
            hdrEnabled = true,
        )

        assertEquals(VideoDynamicRangeDecision.UseSdr, result)
    }
}
