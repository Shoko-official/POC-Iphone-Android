package com.poc.camera.camera

/**
 * Pure state-reset decisions for the switch-camera chip (issue #71), kept free of CameraX
 * classes the same way TorchLogic/ZoomLogic are: CameraScreen owns zoom/torch as plain
 * Compose state and applies [SwitchReset] to it itself, then flips its `lensFacing` state -
 * CameraPreview's own `lensFacing` DisposableEffect key is what actually triggers the rebind.
 *
 * Reset rules:
 * - Zoom resets to 1.0x. The front and back lenses report independent
 *   [androidx.camera.core.ZoomState] ranges (different min/max, different native focal
 *   length), so a ratio dialled in on one lens has no meaningful equivalent on the other.
 *   CameraPreview.reapplyPendingZoom would clamp a carried-over ratio into the new lens's
 *   own range anyway (same as it does across a mode switch), but silently reinterpreting
 *   "2.5x back" as some other ratio on the front lens is confusing UX, not a genuinely
 *   preserved zoom - so the reset happens explicitly here instead of relying on clamping.
 * - Flash mode is *not* reset: it is a deliberate user choice, the same way it already
 *   survives every Photo <-> Video <-> Cinematic mode switch. CameraScreen's existing
 *   `hasFlashUnit` gate (read from the freshly-bound CameraHandle) already hides the flash
 *   control - and makes Auto/On a no-op - on whichever lens has no flash unit, so no
 *   explicit reset is needed for it here.
 * - Torch is forced off: it is a physical light tied to the lens about to be unbound, the
 *   same "off first" rule [TorchLogic.torchEnabledAfterModeChange] applies on every mode
 *   change, applied here to a lens change instead. CameraPreview's own onDispose torch-off
 *   safety net only fires once the old bind actually tears down, so resetting the Compose
 *   state here is what updates the on-screen torch chip immediately rather than leaving it
 *   showing "on" for a torch that a front lens likely doesn't even have.
 */
object CameraSwitchLogic {

    private const val RESET_ZOOM_RATIO = 1f

    /** Zoom/torch values applied the instant the switch-camera chip is tapped. */
    data class SwitchReset(
        val zoomRatio: Float,
        val torchEnabled: Boolean,
    )

    /** See the class doc for why zoom always resets and torch is always forced off. */
    fun resetsForSwitch(): SwitchReset = SwitchReset(zoomRatio = RESET_ZOOM_RATIO, torchEnabled = false)

    /** The chip is hidden entirely unless the device actually reports both cameras. */
    fun shouldShowSwitchChip(hasBackCamera: Boolean, hasFrontCamera: Boolean): Boolean =
        hasBackCamera && hasFrontCamera

    /** Switching mid-recording is out of scope for issue #71; the chip disables instead. */
    fun isSwitchEnabled(isRecording: Boolean): Boolean = !isRecording
}
