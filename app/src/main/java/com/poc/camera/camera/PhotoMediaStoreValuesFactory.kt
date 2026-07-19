package com.poc.camera.camera

object PhotoMediaStoreValuesFactory {
    const val RELATIVE_PATH = "Pictures/POC-Camera"
    private const val MIME_TYPE = "image/jpeg"
    private const val PREFIX = "IMG_"
    private const val EXTENSION = ".jpg"

    fun create(
        timestampMillis: Long,
        supportsPendingFlag: Boolean,
        relativePath: String = RELATIVE_PATH,
    ): PhotoMediaStoreValues = PhotoMediaStoreValues(
        displayName = MediaNaming.displayName(timestampMillis, PREFIX, EXTENSION),
        mimeType = MIME_TYPE,
        relativePath = relativePath,
        isPending = supportsPendingFlag,
    )
}
