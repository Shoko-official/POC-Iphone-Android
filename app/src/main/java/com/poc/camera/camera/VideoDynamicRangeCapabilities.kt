package com.poc.camera.camera

import androidx.camera.core.CameraInfo
import androidx.camera.video.Recorder

/**
 * Reads the CameraX-reported supported dynamic ranges for the given camera, isolating the
 * Recorder/DynamicRange calls from the pure [VideoDynamicRangeResolver]. Verified against
 * camera-video 1.5.0 via javap: Recorder.getVideoCapabilities(CameraInfo) is a static method
 * returning VideoCapabilities, whose getSupportedDynamicRanges() returns Set<DynamicRange>.
 */
object VideoDynamicRangeCapabilities {
    fun resolve(cameraInfo: CameraInfo): Set<SupportedRange> =
        Recorder.getVideoCapabilities(cameraInfo)
            .supportedDynamicRanges
            .map { SupportedRange(encoding = it.encoding, bitDepth = it.bitDepth) }
            .toSet()
}
