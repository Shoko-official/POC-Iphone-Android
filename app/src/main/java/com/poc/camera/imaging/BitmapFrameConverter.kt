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
}
