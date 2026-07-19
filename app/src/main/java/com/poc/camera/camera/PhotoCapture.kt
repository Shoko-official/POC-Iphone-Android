package com.poc.camera.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat

/**
 * Wires the pure MediaStore value builders to CameraX's takePicture call.
 */
object PhotoCapture {

    fun capture(
        context: Context,
        imageCapture: ImageCapture,
        onSuccess: (Uri) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val values = PhotoMediaStoreValuesFactory.create(
            timestampMillis = System.currentTimeMillis(),
            supportsPendingFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        val contentValues = PhotoContentValuesAdapter.toContentValues(values)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        onSuccess(savedUri)
                    } else {
                        onError(ImageCaptureException(ImageCapture.ERROR_FILE_IO, "Saved URI was null", null))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }
}
