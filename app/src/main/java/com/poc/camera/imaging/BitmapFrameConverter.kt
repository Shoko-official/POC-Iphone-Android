package com.poc.camera.imaging

import android.graphics.Bitmap
import com.poc.camera.pipeline.Frame

/**
 * Converts a decoded [Bitmap] into a pipeline [Frame]. `Bitmap.getPixels` already packs
 * pixels as 0xAARRGGBB, matching the ARGB layout used throughout `pipeline/`.
 *
 * Lives in a neutral shared package because both the burst capture path (`camera/`) and
 * the on-device comparison screen (`compare/`) consume it, and `camera/` already depends
 * on `compare/` for [com.poc.camera.compare.ComparePair]; a shared home avoids a package
 * cycle. Thin Android adapter (untested); [Frame] and the quality metrics that consume it
 * are covered by JVM unit tests.
 */
object BitmapFrameConverter {

    fun toFrame(bitmap: Bitmap, timestampMillis: Long = 0L): Frame {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return Frame(width = width, height = height, argb = pixels, timestampMillis = timestampMillis)
    }

    /**
     * Reverse of [toFrame]: an immutable ARGB_8888 [Bitmap] view of [frame]'s pixels, for
     * callers that need a Bitmap-based Android API (e.g. [MergedPhotoSaver][com.poc.camera.camera.MergedPhotoSaver]'s
     * JPEG compression, or [SubjectSegmenter][com.poc.camera.camera.SubjectSegmenter]'s
     * `InputImage.fromBitmap`). `Bitmap.createBitmap(int[], ...)` copies [Frame.argb] rather
     * than wrapping it, so mutating the source frame afterward never affects the bitmap.
     */
    fun fromFrame(frame: Frame): Bitmap =
        Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
}
