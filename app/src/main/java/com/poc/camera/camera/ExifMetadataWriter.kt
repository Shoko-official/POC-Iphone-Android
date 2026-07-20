package com.poc.camera.camera

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Writes an [ExifMetadata] map onto an already-saved MediaStore image via
 * [ExifInterface]. Thin Android adapter (untested on JVM); the tag map it writes is pure
 * and covered by [ExifMetadataTest].
 *
 * Metadata loss must never fail a save that otherwise succeeded - a photo with no EXIF is
 * still a usable photo - so any failure here is logged and swallowed rather than
 * propagated to the caller.
 */
object ExifMetadataWriter {
    private const val TAG = "ExifMetadataWriter"

    fun write(resolver: ContentResolver, uri: Uri, metadata: ExifMetadata) {
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                metadata.toExifAttributes().forEach { (tag, value) ->
                    exif.setAttribute(tag, value)
                }
                exif.saveAttributes()
            } ?: Log.w(TAG, "Could not open file descriptor for $uri to write EXIF")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write EXIF metadata for $uri", e)
        }
    }
}
