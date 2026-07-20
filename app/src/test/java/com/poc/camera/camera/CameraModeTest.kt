package com.poc.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeTest {

    @Test
    fun videoIsVideoLike() {
        assertTrue(CameraMode.Video.isVideoLike)
    }

    @Test
    fun cinematicIsVideoLike() {
        assertTrue(CameraMode.Cinematic.isVideoLike)
    }

    @Test
    fun photoIsNotVideoLike() {
        assertFalse(CameraMode.Photo.isVideoLike)
    }

    @Test
    fun portraitIsNotVideoLike() {
        assertFalse(CameraMode.Portrait.isVideoLike)
    }
}
