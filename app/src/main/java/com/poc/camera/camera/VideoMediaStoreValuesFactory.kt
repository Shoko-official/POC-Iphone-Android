package com.poc.camera.camera

object VideoMediaStoreValuesFactory {
    const val RELATIVE_PATH = "Movies/POC-Camera"
    private const val MIME_TYPE = "video/mp4"
    private const val PREFIX = "VID_"
    private const val EXTENSION = ".mp4"

    fun create(
        timestampMillis: Long,
        relativePath: String = RELATIVE_PATH,
    ): VideoMediaStoreValues = VideoMediaStoreValues(
        displayName = MediaNaming.displayName(timestampMillis, PREFIX, EXTENSION),
        mimeType = MIME_TYPE,
        relativePath = relativePath,
    )
}
