package com.poc.camera.camera

import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.pipeline.Looks

private const val TAG = "CameraPreview"

/**
 * What tap-to-focus and pinch-to-zoom need from the current bind: the bound camera's
 * control surface, its info/zoom-state source, and the PreviewView's
 * coordinate-to-[androidx.camera.core.MeteringPoint] factory. Re-supplied on every
 * successful bind (mode switch, retry) and cleared to null while unbound, so a stale
 * [CameraControl]/[CameraInfo] from a previous bind is never used.
 */
data class CameraHandle(
    val cameraControl: CameraControl,
    val cameraInfo: CameraInfo,
    val meteringPointFactory: MeteringPointFactory,
)

@Composable
fun CameraPreview(
    mode: CameraMode,
    modifier: Modifier = Modifier,
    look: VideoLook = VideoLook.Neutral,
    burstFrameCount: Int = BurstController.DEFAULT_FRAME_COUNT,
    retryToken: Int = 0,
    // Zoom ratio the caller wants applied as soon as a (new) camera binds - e.g. the ratio
    // the user had dialed in before switching modes. Read once per bind via
    // rememberUpdatedState below, not a DisposableEffect key: changing it while already
    // bound (every pinch delta) must never force a camera rebind.
    desiredZoomRatio: Float = 1f,
    // Photo-mode flash setting. setFlashMode is dynamic on ImageCapture, so like zoom this is
    // read via rememberUpdatedState rather than a DisposableEffect key - cycling the flash
    // control while already bound must never force a camera rebind.
    flashMode: FlashMode = FlashMode.Off,
    // Video-mode/Cinematic HDR setting. Unlike flashMode/desiredZoomRatio this is a
    // DisposableEffect key (below) rather than read via rememberUpdatedState: the dynamic
    // range is baked into VideoCapture at build time (VideoCapture.Builder.setDynamicRange),
    // so toggling it while already bound requires a rebind to actually take effect.
    hdrVideoEnabled: Boolean = false,
    onImageCaptureReady: (ImageCapture) -> Unit = {},
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit = {},
    onBurstControllerReady: (BurstController) -> Unit = {},
    onExposureControllerReady: (ExposureController?) -> Unit = {},
    onCinematicConfigReady: (CinematicConfig) -> Unit = {},
    // Reports whether the just-bound Video/Cinematic use case actually resolved to HLG10
    // (true) or SDR (false) - see VideoDynamicRangeResolver. Never called for Photo.
    onVideoRangeResolved: (Boolean) -> Unit = {},
    onCameraReady: (CameraHandle?) -> Unit = {},
    onBindError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .build()
    }
    val burstController = remember(burstFrameCount) { BurstController(burstFrameCount) }
    val currentDesiredZoomRatio by rememberUpdatedState(desiredZoomRatio)
    val currentFlashMode by rememberUpdatedState(flashMode)

    // Bind per mode rather than all use cases at once: some devices reject the combined
    // Preview + ImageCapture + VideoCapture graph, so each mode only binds what it needs.
    // retryToken carries no data; bumping it from the caller re-runs a failed bind attempt.
    DisposableEffect(lifecycleOwner, mode, look, burstFrameCount, retryToken, hdrVideoEnabled) {
        var cameraProvider: ProcessCameraProvider? = null
        var activeEffect: LookCameraEffect? = null
        // Torch is a physical light that outlives whichever use case turned it on; tracked
        // separately from cameraProvider so onDispose can explicitly switch it off before
        // unbinding, regardless of which mode (or dispose reason) triggered the teardown.
        var boundCamera: Camera? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }

                    provider.unbindAll()
                    when (mode) {
                        CameraMode.Photo -> {
                            // Burst now captures sequential full-resolution ImageCapture
                            // frames (driven from CameraScreen), so no ImageAnalysis use
                            // case is bound; Preview + ImageCapture cover both the single
                            // shot and the burst.
                            // setFlashMode is dynamic and safe to call before bind even on a
                            // device with no flash unit at all (CameraX simply has nothing to
                            // apply it to).
                            imageCapture.flashMode = currentFlashMode.toImageCaptureFlashMode()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                            boundCamera = camera
                            onImageCaptureReady(imageCapture)
                            onBurstControllerReady(burstController)
                            onExposureControllerReady(camera.exposureController())
                            onCameraReady(camera.cameraHandle(previewView))
                            camera.reapplyPendingZoom(currentDesiredZoomRatio)
                        }
                        CameraMode.Video -> {
                            // Dynamic range, like the Cinematic fps/stabilization config below,
                            // depends on what this specific camera reports, so it's resolved
                            // fresh on each bind rather than baked into a top-level remember.
                            val cameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                                .filter(provider.availableCameraInfos)
                                .firstOrNull()
                            val supportedRanges = cameraInfo
                                ?.let { VideoDynamicRangeCapabilities.resolve(it) }
                                .orEmpty()
                            val rangeDecision = VideoDynamicRangeResolver.resolve(supportedRanges, hdrVideoEnabled)

                            val videoCapture = VideoCapture.Builder(recorder)
                                .apply {
                                    if (rangeDecision == VideoDynamicRangeDecision.UseHlg10) {
                                        setDynamicRange(DynamicRange.HLG_10_BIT)
                                    }
                                }
                                .build()

                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                videoCapture,
                            )
                            boundCamera = camera
                            onVideoCaptureReady(videoCapture)
                            onVideoRangeResolved(rangeDecision == VideoDynamicRangeDecision.UseHlg10)
                            onCameraReady(camera.cameraHandle(previewView))
                            camera.reapplyPendingZoom(currentDesiredZoomRatio)
                        }
                        CameraMode.Cinematic -> {
                            // Characteristics vary per device, so the config is resolved fresh
                            // on each bind rather than cached alongside the remembered use cases.
                            val cameraInfo = CameraSelector.DEFAULT_BACK_CAMERA
                                .filter(provider.availableCameraInfos)
                                .firstOrNull()
                            val cinematicConfig = cameraInfo
                                ?.let { CinematicCameraCharacteristics.resolve(it) }
                                ?: CinematicConfig(use24Fps = false, stabilization = StabilizationChoice.OFF)

                            val supportedRanges = cameraInfo
                                ?.let { VideoDynamicRangeCapabilities.resolve(it) }
                                .orEmpty()
                            val rawRangeDecision = VideoDynamicRangeResolver.resolve(supportedRanges, hdrVideoEnabled)
                            // Precedence: the GL LUT path (LookCameraEffect/LutSurfaceProcessor)
                            // is an OES/SDR-oriented pipeline with no 10-bit HLG support in this
                            // POC, so a non-neutral look always wins over HDR - HLG is only ever
                            // requested when the look is Neutral. onVideoRangeResolved below
                            // reports this effective decision, never the raw one, so the overlay
                            // can never claim HLG while the effect actually forces SDR.
                            val rangeDecision = if (look != VideoLook.Neutral) {
                                VideoDynamicRangeDecision.UseSdr
                            } else {
                                rawRangeDecision
                            }

                            val cinematicVideoCapture = VideoCapture.Builder(recorder)
                                .apply {
                                    if (cinematicConfig.use24Fps) {
                                        val fps = CinematicConfigResolver.TARGET_FPS
                                        setTargetFrameRate(Range(fps, fps))
                                    }
                                    if (rangeDecision == VideoDynamicRangeDecision.UseHlg10) {
                                        setDynamicRange(DynamicRange.HLG_10_BIT)
                                    }
                                }
                                .setVideoStabilizationEnabled(cinematicConfig.stabilization == StabilizationChoice.ON)
                                .build()

                            // Only the Cinematic look enters the GL path; Neutral binds plainly for
                            // zero overhead. Effect construction (incl. EGL init) is guarded so any
                            // GL failure logs and falls back to binding without a look.
                            val effect = if (look == VideoLook.Cinematic) {
                                try {
                                    LookCameraEffect.create(Looks.cinematic(LookCameraEffect.LUT_SIZE))
                                } catch (t: Throwable) {
                                    Log.e(TAG, "LUT effect init failed; binding without look", t)
                                    null
                                }
                            } else {
                                null
                            }
                            activeEffect = effect

                            val camera = if (effect != null) {
                                val useCaseGroup = UseCaseGroup.Builder()
                                    .addUseCase(preview)
                                    .addUseCase(cinematicVideoCapture)
                                    .addEffect(effect)
                                    .build()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    useCaseGroup,
                                )
                            } else {
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    cinematicVideoCapture,
                                )
                            }
                            boundCamera = camera
                            onVideoCaptureReady(cinematicVideoCapture)
                            onCinematicConfigReady(cinematicConfig)
                            onVideoRangeResolved(rangeDecision == VideoDynamicRangeDecision.UseHlg10)
                            onCameraReady(camera.cameraHandle(previewView))
                            camera.reapplyPendingZoom(currentDesiredZoomRatio)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera preview bind failed", t)
                    onBindError(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            // Explicitly off before unbinding: a defensive no-op on devices where closing
            // the camera already kills the torch, and the one call that matters on devices
            // where it doesn't. Fire-and-forget - nothing left to observe the result of once
            // the bind is torn down.
            boundCamera?.cameraControl?.enableTorch(false)
            cameraProvider?.unbindAll()
            activeEffect?.release()
            // Invalidates any in-flight camera handle from this bind so a mode switch or
            // retry never lets a tap or pinch reach a torn-down CameraControl.
            onCameraReady(null)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Builds an [ExposureController] from this camera's reported exposure-compensation
 * state, or null when the device does not support compensation at all. The returned
 * [ExposureController.setIndexAndAwait] drives [androidx.camera.core.CameraControl]
 * and blocks on its future, so it must be invoked off the main thread.
 */
private fun Camera.exposureController(): ExposureController? {
    val exposureState = cameraInfo.exposureState
    if (!exposureState.isExposureCompensationSupported) return null

    val step = exposureState.exposureCompensationStep
    val range = exposureState.exposureCompensationRange
    val plan = ExposureBracket.plan(
        stepNumerator = step.numerator,
        stepDenominator = step.denominator,
        rangeLower = range.lower,
        rangeUpper = range.upper,
    )
    return ExposureController(
        steps = plan,
        framesPerEv = ExposureBracket.DEFAULT_FRAMES_PER_EV,
        setIndexAndAwait = { index ->
            // Blocks until the compensation change is applied; the awaited future is
            // the best-effort AE-settle signal without Camera2 interop.
            cameraControl.setExposureCompensationIndex(index).get()
        },
    )
}

private fun Camera.cameraHandle(previewView: PreviewView): CameraHandle =
    CameraHandle(cameraControl, cameraInfo, previewView.meteringPointFactory)

/**
 * Re-applies a zoom ratio carried over from a previous bind (e.g. the ratio the user had
 * dialed in before switching mode), clamped to *this* camera's own reported range so state
 * is preserved across mode switches only where the new camera can actually honour it. A
 * ratio of 1x or below is never worth an explicit call - every camera already starts at its
 * own native 1x-equivalent baseline. If the zoom-state LiveData has no value yet (bind
 * still settling), the previous ratio is simply dropped rather than guessed at; the chip
 * will reflect whatever the camera actually starts at.
 */
private fun Camera.reapplyPendingZoom(desiredZoomRatio: Float) {
    if (!desiredZoomRatio.isFinite() || desiredZoomRatio <= 1f) return
    val zoomState = cameraInfo.zoomState.value ?: return
    val clamped = ZoomLogic.computeNewRatio(
        current = 1f,
        gestureFactor = desiredZoomRatio,
        min = zoomState.minZoomRatio,
        max = zoomState.maxZoomRatio,
    )
    cameraControl.setZoomRatio(clamped)
}
