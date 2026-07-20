package com.poc.camera.compare

import android.graphics.Bitmap
import com.poc.camera.pipeline.Frame

/**
 * Converts a decoded [Bitmap] back into a [Frame] for quality-metric comparison.
 * `Bitmap.getPixels` already packs pixels as 0xAARRGGBB, matching the ARGB layout
 * used throughout `pipeline/`. Thin Android adapter (untested); [Frame] and the
 * quality metrics that consume it are covered by JVM unit tests.
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
