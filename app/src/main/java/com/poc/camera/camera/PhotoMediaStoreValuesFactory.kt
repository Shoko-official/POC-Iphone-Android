package com.poc.camera.camera

object PhotoMediaStoreValuesFactory {
    const val RELATIVE_PATH = "Pictures/POC-Camera"
    const val DEFAULT_PREFIX = "IMG_"
    private const val MIME_TYPE = "image/jpeg"
    private const val EXTENSION = ".jpg"

    fun create(
        timestampMillis: Long,
        supportsPendingFlag: Boolean,
        relativePath: String = RELATIVE_PATH,
        prefix: String = DEFAULT_PREFIX,
    ): PhotoMediaStoreValues = PhotoMediaStoreValues(
        displayName = MediaNaming.displayName(timestampMillis, prefix, EXTENSION),
        mimeType = MIME_TYPE,
        relativePath = relativePath,
        isPending = supportsPendingFlag,
    )
}
