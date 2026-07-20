package com.poc.camera.camera

import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import com.poc.camera.settings.VideoQualityChoice

/**
 * Reads the CameraX-reported supported video qualities for the given camera and dynamic
 * range, isolating the Recorder/Quality calls from the pure [com.poc.camera.settings.VideoQualityLogic] -
 * mirrors [VideoDynamicRangeCapabilities]. Verified against camera-video 1.5.0 via javap:
 * Recorder.getVideoCapabilities(CameraInfo) is a static method returning VideoCapabilities,
 * whose getSupportedQualities(DynamicRange) returns List<Quality>.
 *
 * Takes the dynamic range as a parameter rather than always querying SDR: HLG and SDR can
 * report different supported-quality sets on the same camera, so the caller must pass the
 * dynamic range it has already resolved for this bind (see VideoDynamicRangeResolver) to get
 * an accurate answer for what's actually about to be requested.
 */
object VideoQualityCapabilities {
    fun resolve(cameraInfo: CameraInfo, dynamicRange: DynamicRange): Set<VideoQualityChoice> =
        Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(dynamicRange)
            .mapNotNull { it.toVideoQualityChoice() }
            .toSet()
}

/** [VideoQualityChoice.UHD] maps to CameraX's [Quality.UHD] (2160p / "4K"). */
fun VideoQualityChoice.toCameraXQuality(): Quality = when (this) {
    VideoQualityChoice.SD -> Quality.SD
    VideoQualityChoice.HD -> Quality.HD
    VideoQualityChoice.FHD -> Quality.FHD
    VideoQualityChoice.UHD -> Quality.UHD
}

/** Null for any CameraX quality outside the four this app exposes (e.g. LOWEST/HIGHEST). */
private fun Quality.toVideoQualityChoice(): VideoQualityChoice? = when (this) {
    Quality.SD -> VideoQualityChoice.SD
    Quality.HD -> VideoQualityChoice.HD
    Quality.FHD -> VideoQualityChoice.FHD
    Quality.UHD -> VideoQualityChoice.UHD
    else -> null
}
