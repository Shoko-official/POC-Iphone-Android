package com.poc.camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat

/**
 * Wires the pure MediaStore value builders to CameraX's Recorder start call.
 */
object VideoRecording {

    fun start(
        context: Context,
        recorder: Recorder,
        onFinalized: (VideoRecordEvent.Finalize) -> Unit,
    ): Recording {
        val values = VideoMediaStoreValuesFactory.create(timestampMillis = System.currentTimeMillis())
        val contentValues = VideoContentValuesAdapter.toContentValues(values)
        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
            .setContentValues(contentValues)
            .build()

        val pendingRecording = recorder.prepareRecording(context, outputOptions)
        // Direct permission check (rather than a boolean captured earlier) so lint's
        // data-flow analysis recognizes the @RequiresPermission guard on withAudioEnabled().
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording.withAudioEnabled()
        }

        return pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                onFinalized(event)
            }
        }
    }
}
