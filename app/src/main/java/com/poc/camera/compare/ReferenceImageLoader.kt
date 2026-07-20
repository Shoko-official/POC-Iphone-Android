package com.poc.camera.compare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Decodes a MediaStore or photo-picker [Uri] into a downsampled [Bitmap], sized
 * with a power-of-two `inSampleSize` so a 12MP+ photo never has to be fully
 * decoded in memory - only the (small) bounds are decoded first. Returns `null`
 * on any failure (revoked grant, missing file, corrupt image) instead of
 * throwing, since a failed reference load is a normal, recoverable UI state, not
 * an exceptional one. Thin Android adapter (untested); [ImageDownsampler] carries
 * the tested pure math.
 */
object ReferenceImageLoader {

    fun loadDownsampled(context: Context, uri: Uri, maxDimensionPx: Int): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = ImageDownsampler.calculateInSampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    reqWidth = maxDimensionPx,
                    reqHeight = maxDimensionPx,
                )
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        }
    } catch (e: Exception) {
        null
    }
}
