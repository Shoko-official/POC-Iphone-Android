package com.poc.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.poc.camera.imaging.BitmapFrameConverter
import com.poc.camera.pipeline.Frame
import java.io.IOException

/**
 * Persists a merged [Frame] as a JPEG via MediaStore, reusing the single-shot
 * photo value builders but with a distinct display-name prefix for each caller: the
 * standard shutter's processed result now shares [PhotoMediaStoreValuesFactory.DEFAULT_PREFIX]
 * ("IMG_") with a plain single-shot capture (issue #101 - the shutter IS the processed
 * path, so there is no longer a distinct "merged" subset of photos to prefix differently),
 * "RAW_" for the unprocessed comparison reference (the merge input frame, saved as-is when
 * "Save comparison pair" is enabled), "PRT_" for a Portrait-mode result (issue #80, whether
 * or not a subject mask was actually found - see CameraScreen's Portrait capture flow). The
 * old burst-only "MRG_" prefix is retired along with it - every caller now passes its prefix
 * explicitly. When [save] is given [exif], it is written onto the file via
 * [ExifMetadataWriter] before the MediaStore row is unpended - a failure there is logged and
 * swallowed rather than failing the save. Thin Android adapter (untested); the deterministic
 * pixel work lives in the pipeline package and is covered by unit tests.
 */
object MergedPhotoSaver {

    const val RAW_PREFIX = "RAW_"
    const val PORTRAIT_PREFIX = "PRT_"

    fun save(context: Context, frame: Frame, prefix: String, exif: ExifMetadata? = null): Uri {
        val bitmap = BitmapFrameConverter.fromFrame(frame)
        // Recycle on every exit path after the allocation - including the null-insert throw
        // below, which fires before the inner try and previously left the (tens-of-MB) bitmap
        // for the GC on storage-full / provider failures.
        try {
            val supportsPendingFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val values = PhotoMediaStoreValuesFactory.create(
                timestampMillis = frame.timestampMillis,
                supportsPendingFlag = supportsPendingFlag,
                prefix = prefix,
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
            }

            if (exif != null) {
                ExifMetadataWriter.write(resolver, uri, exif)
            }

            if (supportsPendingFlag) {
                val pendingCleared = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, pendingCleared, null, null)
            }

            return uri
        } finally {
            bitmap.recycle()
        }
    }

    private const val JPEG_QUALITY = 95
}
