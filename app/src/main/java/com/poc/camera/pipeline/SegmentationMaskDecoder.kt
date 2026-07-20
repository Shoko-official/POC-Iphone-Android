package com.poc.camera.pipeline

import java.nio.ByteBuffer

/**
 * Decodes an MLKit `SegmentationMask.getBuffer()` result (issue #80) into a plain
 * [FloatArray].
 *
 * Per MLKit's documented layout, the buffer holds one 4-byte float per pixel, row-major
 * (`width * height * 4` bytes total), in the platform's native byte order, each value in
 * [0, 1] giving the foreground (subject) confidence at that pixel - i.e. already the soft
 * mask contract [BlurMap]/[BokehRenderer] expect.
 *
 * `java.nio.ByteBuffer` is a plain JVM type, not an Android or MLKit one, so this stays
 * pure Kotlin and is unit-tested directly against synthetic buffers built the same way -
 * no Robolectric or device needed. [com.poc.camera.camera.SubjectSegmenter] is the only
 * caller that ever touches an actual MLKit-produced buffer.
 */
object SegmentationMaskDecoder {

    fun toFloatArray(buffer: ByteBuffer, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "width and height must be > 0" }
        val size = width * height
        val requiredBytes = size * 4
        require(buffer.remaining() >= requiredBytes) {
            "buffer has ${buffer.remaining()} bytes remaining, need at least $requiredBytes for $width x $height"
        }
        // duplicate() shares the backing storage but gets its own position/limit/mark, so
        // decoding never disturbs the caller's buffer state - EXCEPT byte order, which
        // ByteBuffer.duplicate() always resets to BIG_ENDIAN regardless of the source
        // buffer's order (a documented java.nio quirk, not a bug: order() must be re-applied
        // explicitly), so it is copied over before building the float view.
        val floatView = buffer.duplicate().order(buffer.order()).asFloatBuffer()
        val out = FloatArray(size)
        floatView.get(out, 0, size)
        return out
    }
}
