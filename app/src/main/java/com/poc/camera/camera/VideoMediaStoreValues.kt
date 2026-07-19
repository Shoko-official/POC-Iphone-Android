package com.poc.camera.camera

/**
 * Plain data describing a MediaStore entry for a recorded video, independent of
 * android.content.ContentValues so it can be produced and asserted on in unit tests.
 */
data class VideoMediaStoreValues(
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
)
