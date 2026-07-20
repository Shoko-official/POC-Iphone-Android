package com.poc.camera.camera

/**
 * Resolves the MIME type handed to `ACTION_VIEW` for a [LastCapture]. Prefers whatever
 * `ContentResolver.getType` actually reports for the URI - the caller passes that result
 * straight through - and falls back to a generic image/video wildcard only when the
 * resolver can't answer (e.g. a revoked grant), keyed off the capture's own media type
 * rather than guessing from the URI string. Pure string logic; the resolver query itself
 * is the untested Android adapter side, in LastCaptureThumbnail.kt.
 */
object LastCaptureMimeType {

    private const val FALLBACK_IMAGE_MIME_TYPE = "image/*"
    private const val FALLBACK_VIDEO_MIME_TYPE = "video/*"

    fun resolve(queriedMimeType: String?, mediaType: CaptureMediaType): String =
        queriedMimeType ?: when (mediaType) {
            CaptureMediaType.Photo -> FALLBACK_IMAGE_MIME_TYPE
            CaptureMediaType.Video -> FALLBACK_VIDEO_MIME_TYPE
        }
}
