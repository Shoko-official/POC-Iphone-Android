package com.poc.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class LastCaptureMimeTypeTest {

    @Test
    fun queriedMimeTypeIsReturnedAsIs() {
        assertEquals(
            "image/jpeg",
            LastCaptureMimeType.resolve(queriedMimeType = "image/jpeg", mediaType = CaptureMediaType.Photo),
        )
    }

    @Test
    fun queriedMimeTypeIsReturnedAsIsForVideo() {
        assertEquals(
            "video/mp4",
            LastCaptureMimeType.resolve(queriedMimeType = "video/mp4", mediaType = CaptureMediaType.Video),
        )
    }

    @Test
    fun nullQueriedMimeTypeFallsBackToImageWildcardForPhoto() {
        assertEquals(
            "image/*",
            LastCaptureMimeType.resolve(queriedMimeType = null, mediaType = CaptureMediaType.Photo),
        )
    }

    @Test
    fun nullQueriedMimeTypeFallsBackToVideoWildcardForVideo() {
        assertEquals(
            "video/*",
            LastCaptureMimeType.resolve(queriedMimeType = null, mediaType = CaptureMediaType.Video),
        )
    }
}
