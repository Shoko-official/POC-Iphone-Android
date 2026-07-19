package com.poc.camera.camera

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Converts [PhotoMediaStoreValues] into a real [ContentValues], branching on the API
 * level detail that the pure factory intentionally does not know about.
 */
object PhotoContentValuesAdapter {

    fun toContentValues(values: PhotoMediaStoreValues): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, values.displayName)
        put(MediaStore.Images.Media.MIME_TYPE, values.mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, values.relativePath)
            if (values.isPending) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        } else {
            val subDirectory = values.relativePath.removePrefix("Pictures/").removePrefix("Pictures")
            val picturesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDirectory = if (subDirectory.isBlank()) picturesRoot else File(picturesRoot, subDirectory)
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            put(MediaStore.Images.Media.DATA, File(targetDirectory, values.displayName).absolutePath)
        }
    }
}
