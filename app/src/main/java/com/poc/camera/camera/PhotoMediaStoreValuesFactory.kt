package com.poc.camera.camera

object PhotoMediaStoreValuesFactory {
    const val RELATIVE_PATH = "Pictures/POC-Camera"
    private const val MIME_TYPE = "image/jpeg"

    fun create(
        timestampMillis: Long,
        supportsPendingFlag: Boolean,
        relativePath: String = RELATIVE_PATH,
    ): PhotoMediaStoreValues = PhotoMediaStoreValues(
        displayName = PhotoCaptureNaming.displayName(timestampMillis),
        mimeType = MIME_TYPE,
        relativePath = relativePath,
        isPending = supportsPendingFlag,
    )
}
