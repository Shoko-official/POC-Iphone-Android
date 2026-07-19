package com.poc.camera.camera

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Converts [VideoMediaStoreValues] into a real [ContentValues], branching on the API
 * level detail that the pure factory intentionally does not know about.
 */
object VideoContentValuesAdapter {

    fun toContentValues(values: VideoMediaStoreValues): ContentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, values.displayName)
        put(MediaStore.Video.Media.MIME_TYPE, values.mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, values.relativePath)
        } else {
            val subDirectory = values.relativePath.removePrefix("Movies/").removePrefix("Movies")
            val moviesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetDirectory = if (subDirectory.isBlank()) moviesRoot else File(moviesRoot, subDirectory)
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            put(MediaStore.Video.Media.DATA, File(targetDirectory, values.displayName).absolutePath)
        }
    }
}
