package com.poc.camera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.poc.camera.imaging.BitmapFrameConverter
import com.poc.camera.pipeline.Frame
import java.io.IOException

/**
 * Decodes raw full-resolution JPEG bytes captured via
 * `ImageCapture.takePicture(Executor, OnImageCapturedCallback)` into an upright pipeline
 * [Frame].
 *
 * [jpegBytes] is decoded with an `inSampleSize` chosen by [BurstImageGeometry] so the result
 * stays within [BurstImageGeometry.MAX_BURST_PIXELS], then rotated to upright per
 * [rotationDegrees] (the source `ImageProxy`'s reported rotation - see [BurstImageCapture],
 * which extracts both before the `ImageProxy` itself is closed, since decode now runs later
 * and off that callback thread - see [BurstController]'s decode-pipelining doc) so the
 * pipeline sees the same orientation the retired ImageAnalysis path produced.
 *
 * Thin Android adapter, exercised only on device; the sizing/rotation math it relies on is
 * pure and unit tested in [BurstImageGeometry], and [Frame] plus the merge pipeline are
 * covered by JVM tests.
 */
object JpegBurstFrameDecoder {

    fun decode(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        maxBurstPixels: Int = BurstImageGeometry.MAX_BURST_PIXELS,
    ): Frame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val sampleSize = BurstImageGeometry.inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxBurstPixels)

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IOException("Failed to decode burst JPEG frame")

        // Rotating allocates a second bitmap of the (rotated) same pixel count for the
        // duration of the conversion, so peak decode memory is ~2x one downscaled frame;
        // both are released before the next capture in the sequential burst loop.
        val upright = rotateUpright(decoded, rotationDegrees)
        return try {
            BitmapFrameConverter.toFrame(upright, System.currentTimeMillis())
        } finally {
            if (upright !== decoded) decoded.recycle()
            upright.recycle()
        }
    }

    private fun rotateUpright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
