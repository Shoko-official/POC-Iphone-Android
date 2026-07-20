package com.poc.camera.camera

/**
 * The exposure-bracketing capability handed from [CameraPreview] up to
 * [CameraScreen] once a Photo-mode camera is bound.
 *
 * It bundles the precomputed [ExposureBracket] plan (resolved from the device's
 * reported compensation range and step) with the blocking action that applies a
 * compensation index and awaits the camera's confirmation. [setIndexAndAwait] MUST
 * be called off the main thread; it drives `CameraControl.setExposureCompensationIndex`
 * and blocks on the returned future, which is the best-effort AE-settle signal
 * available without Camera2 interop.
 */
class ExposureController(
    val steps: List<ExposureBracket.BracketStep>,
    val framesPerEv: Int,
    val setIndexAndAwait: (Int) -> Unit,
)
