package com.poc.camera.camera

import com.poc.camera.pipeline.Lut3d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [LutAtlasTexture]. The GL upload in [LutSurfaceProcessor]
 * itself is device-only and untestable here (no Robolectric); these lock in the
 * geometry/alignment facts that justify its `GL_UNPACK_ALIGNMENT = 1` upload.
 */
class LutAtlasTextureTest {

    @Test
    fun shippedSize17RowIsNotFourAligned() {
        // 17*17 = 289 texels per row * 3 bytes = 867 bytes; 867 % 4 == 3.
        // The GL default GL_UNPACK_ALIGNMENT of 4 would therefore misread the
        // atlas, which is exactly why the processor sets it to 1.
        assertEquals(867, LutAtlasTexture.rowStrideBytes(17))
        assertEquals(3, LutAtlasTexture.rowStrideBytes(17) % 4)
        assertFalse(LutAtlasTexture.isRowFourAligned(17))
    }

    @Test
    fun byteLengthIsWidthTimesHeightTimesThree() {
        for (size in intArrayOf(2, 9, 16, 17, 33)) {
            val expected = LutAtlasTexture.width(size) * LutAtlasTexture.height(size) * 3
            assertEquals(expected, LutAtlasTexture.byteLength(size))
        }
        // Concrete anchor for the shipped size: 289 * 17 * 3 = 14739.
        assertEquals(14739, LutAtlasTexture.byteLength(17))
    }

    @Test
    fun byteLengthMatchesRealLut3dAtlas() {
        // The helper must agree with the actual atlas the processor uploads, so a
        // future change to Lut3d.toAtlasRgb layout cannot silently desync the
        // alignment reasoning from the real byte buffer.
        for (size in intArrayOf(2, 9, 17)) {
            val atlasFloats = Lut3d.identity(size).toAtlasRgb().size
            assertEquals(atlasFloats, LutAtlasTexture.byteLength(size))
        }
    }

    @Test
    fun dimensionsFollowTheSliceLayout() {
        // width = size blue slices, each size wide; height = size (one row per green).
        assertEquals(289, LutAtlasTexture.width(17))
        assertEquals(17, LutAtlasTexture.height(17))
        assertEquals(4, LutAtlasTexture.width(2))
        assertEquals(2, LutAtlasTexture.height(2))
    }

    @Test
    fun someSizesAreFourAligned() {
        // A power-of-two-friendly row stride can be 4-aligned; the fix must not
        // depend on the atlas always being misaligned. size 4: 16*3 = 48 bytes.
        assertTrue(LutAtlasTexture.isRowFourAligned(4))
        assertEquals(0, LutAtlasTexture.rowStrideBytes(4) % 4)
    }
}
