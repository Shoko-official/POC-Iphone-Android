package com.poc.camera.camera

/**
 * Pure geometry of the 2D RGB texture that a [com.poc.camera.pipeline.Lut3d] of a
 * given lattice size is uploaded as (see [com.poc.camera.pipeline.Lut3d.toAtlasRgb]):
 * N blue slices of N×N laid out horizontally, so
 *
 *   width  = size * size
 *   height = size
 *   3 tightly packed bytes per texel (RGB / GL_UNSIGNED_BYTE)
 *
 * This is extracted out of [LutSurfaceProcessor] so the row-stride/alignment
 * reasoning behind its `glPixelStorei(GL_UNPACK_ALIGNMENT, 1)` upload is
 * unit-testable without a GL context (the processor itself only runs on a device
 * GPU). GL's default `GL_UNPACK_ALIGNMENT` is 4: each texel row is assumed to
 * start on a 4-byte boundary. A tightly packed RGB row whose byte length is not a
 * multiple of 4 is therefore misread from the second row onwards unless the unpack
 * alignment is set to 1 before `glTexImage2D`. The shipped 17-lattice atlas has a
 * row of 289 × 3 = 867 bytes (867 % 4 == 3), so it is precisely such a case.
 */
object LutAtlasTexture {

    /** Tightly packed RGB: one byte per channel, no per-texel padding. */
    const val BYTES_PER_TEXEL = 3

    /**
     * The `GL_UNPACK_ALIGNMENT` value under which any tightly packed RGB row
     * uploads correctly regardless of its byte length.
     */
    const val REQUIRED_UNPACK_ALIGNMENT = 1

    /** Atlas width in texels: `size` blue slices, each `size` texels wide. */
    fun width(size: Int): Int = size * size

    /** Atlas height in texels: one row per green index. */
    fun height(size: Int): Int = size

    /** Byte length of a single texel row = [width] × [BYTES_PER_TEXEL]. */
    fun rowStrideBytes(size: Int): Int = width(size) * BYTES_PER_TEXEL

    /** Total tightly packed byte length of the atlas = width × height × 3. */
    fun byteLength(size: Int): Int = width(size) * height(size) * BYTES_PER_TEXEL

    /**
     * Whether a texel row starts on a 4-byte boundary, i.e. whether the GL
     * default `GL_UNPACK_ALIGNMENT` of 4 would upload the atlas correctly. When
     * false, [REQUIRED_UNPACK_ALIGNMENT] must be set before the upload.
     */
    fun isRowFourAligned(size: Int): Boolean = rowStrideBytes(size) % 4 == 0
}
