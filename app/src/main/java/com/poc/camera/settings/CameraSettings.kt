package com.poc.camera.settings

/** Loads and persists [CameraSettingsData]. See [SharedPreferencesCameraSettings] for the platform adapter. */
interface CameraSettings {
    fun load(): CameraSettingsData
    fun save(data: CameraSettingsData)
}
