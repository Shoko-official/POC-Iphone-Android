package com.poc.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.poc.camera.pipeline.Frame
import java.io.IOException

/**
 * Persists a merged [Frame] as a JPEG via MediaStore, reusing the single-shot
 * photo value builders but with a distinct "MRG_" display-name prefix. Thin
 * Android adapter (untested); the deterministic pixel work lives in the pipeline
 * package and is covered by unit tests.
 */
object MergedPhotoSaver {

    fun save(context: Context, frame: Frame): Uri {
        val bitmap = Bitmap.createBitmap(
            frame.argb,
            frame.width,
            frame.height,
            Bitmap.Config.ARGB_8888,
        )
        val supportsPendingFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val values = PhotoMediaStoreValuesFactory.create(
            timestampMillis = frame.timestampMillis,
            supportsPendingFlag = supportsPendingFlag,
            prefix = MERGED_PREFIX,
        )
        val contentValues = PhotoContentValuesAdapter.toContentValues(values)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IOException("JPEG compression failed")
                }
            } ?: throw IOException("Could not open output stream for $uri")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            bitmap.recycle()
        }

        if (supportsPendingFlag) {
            val pendingCleared = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, pendingCleared, null, null)
        }

        return uri
    }

    private const val MERGED_PREFIX = "MRG_"
    private const val JPEG_QUALITY = 95
}
