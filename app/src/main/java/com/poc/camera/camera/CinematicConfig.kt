package com.poc.camera.camera

enum class StabilizationChoice {
    ON,
    OFF,
}

data class CinematicConfig(
    val use24Fps: Boolean,
    val stabilization: StabilizationChoice,
)
