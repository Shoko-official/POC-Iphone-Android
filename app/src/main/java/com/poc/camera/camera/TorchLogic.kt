package com.poc.camera.camera

/**
 * Torch (continuous video-mode light) state logic, kept free of CameraX classes: CameraScreen
 * owns `torchEnabled` as plain Compose state and applies it to `CameraControl.enableTorch`
 * itself whenever it changes; this object only decides the pure state transitions.
 */
object TorchLogic {

    /** Flips the torch toggle - the value applied on every top-status torch button tap. */
    fun toggle(current: Boolean): Boolean = !current

    /**
     * Torch is only ever a user-facing control in Video/Cinematic, so leaving a video-like
     * mode for Photo always resets it to off rather than carrying an "invisible" torch state
     * into a mode with no torch control (CameraPreview also turns the physical torch off on
     * every bind teardown as a hardware-level safety net - see its onDispose). Re-entering a
     * video-like mode keeps [previousTorchEnabled] as-is: it is only ever non-false immediately
     * after switching *between* video-like modes (Video <-> Cinematic), since any transition
     * through Photo has already forced it back to false.
     */
    fun torchEnabledAfterModeChange(newMode: CameraMode, previousTorchEnabled: Boolean): Boolean =
        if (newMode.isVideoLike) previousTorchEnabled else false
}
