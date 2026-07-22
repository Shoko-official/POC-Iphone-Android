package com.poc.camera.camera

import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
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
import com.poc.camera.settings.VideoQualityChoice
import com.poc.camera.settings.VideoQualityLogic

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
    // Which physical camera to bind (issue #71). A DisposableEffect key (below), like mode:
    // the selector is baked into every bindToLifecycle call, so switching lenses always
    // needs a full rebind, never a dynamic in-place update.
    lensFacing: LensFacing = LensFacing.Back,
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
    // Video-mode/Cinematic quality setting (issue #72). Like hdrVideoEnabled, a
    // DisposableEffect key rather than rememberUpdatedState: QualitySelector is fixed at
    // Recorder-build time (Recorder.Builder.setQualitySelector), so the Recorder itself is
    // now built per-bind (see below) and a quality change needs a full rebind to take effect.
    videoQuality: VideoQualityChoice = VideoQualityChoice.FHD,
    onImageCaptureReady: (ImageCapture) -> Unit = {},
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit = {},
    onBurstControllerReady: (BurstController) -> Unit = {},
    onExposureControllerReady: (ExposureController?) -> Unit = {},
    onCinematicConfigReady: (CinematicConfig) -> Unit = {},
    // Reports the just-bound Video/Cinematic use case's actual resolved dynamic range
    // (isHlg - see VideoDynamicRangeResolver) and quality (effectiveQuality - see
    // VideoQualityLogic), which may each differ from what settings/hdrVideoEnabled and
    // videoQuality asked for once device capabilities are taken into account. Never called
    // for Photo.
    onVideoConfigResolved: (isHlg: Boolean, effectiveQuality: VideoQualityChoice) -> Unit = { _, _ -> },
    // Reports which of the two default cameras (issue #71) the device actually has, via
    // ProcessCameraProvider.hasCamera, once the provider is ready - independent of `mode`/
    // `lensFacing`, so CameraScreen can gate the switch-camera chip's visibility
    // (CameraSwitchLogic.shouldShowSwitchChip) before the user ever taps it.
    onCameraAvailability: (hasBackCamera: Boolean, hasFrontCamera: Boolean) -> Unit = { _, _ -> },
    onCameraReady: (CameraHandle?) -> Unit = {},
    onBindError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    // Mirroring (issue #71): PreviewView mirrors the front camera's live preview
    // automatically and unconditionally - that part needs no code here at all. ImageCapture
    // is deliberately left at its default (unmirrored) output: CameraX's own
    // ImageCapture.Builder.setMirrorMode exists but is not documented as affecting the
    // actual saved JPEG bytes (unlike VideoCapture's, added and documented for video in
    // CameraX 1.3 - see the VideoCapture.Builder calls below), and the platform convention
    // for front-camera stills is to save what the sensor delivered - readable text stays
    // readable - rather than silently flipping the file to match the mirrored preview. No
    // transformation is added here; the burst/guided-compare merge path already just
    // decodes whatever bytes ImageCapture produced (see BurstImageCapture/MergedPhotoSaver),
    // for either lens, unchanged by this issue.
    val imageCapture = remember { ImageCapture.Builder().build() }
    val burstController = remember(burstFrameCount) { BurstController(burstFrameCount) }
    val currentDesiredZoomRatio by rememberUpdatedState(desiredZoomRatio)
    val currentFlashMode by rememberUpdatedState(flashMode)

    // Bind per mode rather than all use cases at once: some devices reject the combined
    // Preview + ImageCapture + VideoCapture graph, so each mode only binds what it needs.
    // retryToken carries no data; bumping it from the caller re-runs a failed bind attempt.
    DisposableEffect(
        lifecycleOwner,
        mode,
        look,
        burstFrameCount,
        retryToken,
        hdrVideoEnabled,
        videoQuality,
        lensFacing,
    ) {
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

                    // Capability gate for the switch-camera chip (issue #71): checked fresh
                    // on every bind (cheap - hasCamera is a metadata lookup, not a hardware
                    // open) rather than cached once, so it also self-corrects if the provider
                    // was somehow not fully warmed up on an earlier call.
                    val hasBackCamera = try {
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    } catch (e: CameraInfoUnavailableException) {
                        false
                    }
                    val hasFrontCamera = try {
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    } catch (e: CameraInfoUnavailableException) {
                        false
                    }
                    onCameraAvailability(hasBackCamera, hasFrontCamera)

                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }

                    provider.unbindAll()
                    when (mode) {
                        // Portrait (issue #80) binds identically to Photo - Preview +
                        // ImageCapture is all it ever needs, since its capture path always
                        // goes through the same full-resolution burst (see CameraScreen's
                        // Portrait capture flow, which ignores the HDR/night/SR toggles
                        // Photo's shutter honours - see startPhotoCapture).
                        CameraMode.Photo, CameraMode.Portrait -> {
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
                                lensFacing.toCameraSelector(),
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
                            val cameraInfo = lensFacing.toCameraSelector()
                                .filter(provider.availableCameraInfos)
                                .firstOrNull()
                            val supportedRanges = cameraInfo
                                ?.let { VideoDynamicRangeCapabilities.resolve(it) }
                                .orEmpty()
                            val rangeDecision = VideoDynamicRangeResolver.resolve(supportedRanges, hdrVideoEnabled)
                            val resolvedDynamicRange = if (rangeDecision == VideoDynamicRangeDecision.UseHlg10) {
                                DynamicRange.HLG_10_BIT
                            } else {
                                DynamicRange.SDR
                            }

                            // Quality (issue #72): HLG and SDR can report different supported-
                            // quality sets on the same camera, so this is queried against the
                            // dynamic range just resolved above, not a fixed range.
                            val supportedQualities = cameraInfo
                                ?.let { VideoQualityCapabilities.resolve(it, resolvedDynamicRange) }
                                .orEmpty()
                            val effectiveQuality = VideoQualityLogic.resolve(supportedQualities, videoQuality)
                            val recorder = Recorder.Builder()
                                .setQualitySelector(
                                    QualitySelector.from(
                                        effectiveQuality.toCameraXQuality(),
                                        // Belt-and-braces final guard: effectiveQuality is
                                        // already expected supported per the capability query
                                        // above, so this only matters if that query was stale
                                        // or incomplete - CameraX's own fallback then takes
                                        // over rather than a hard bind failure.
                                        FallbackStrategy.higherQualityOrLowerThan(effectiveQuality.toCameraXQuality()),
                                    ),
                                )
                                .build()

                            val videoCapture = VideoCapture.Builder(recorder)
                                .apply {
                                    if (rangeDecision == VideoDynamicRangeDecision.UseHlg10) {
                                        setDynamicRange(DynamicRange.HLG_10_BIT)
                                    }
                                }
                                // Mirroring (issue #71): PreviewView already mirrors the front
                                // camera's live preview unconditionally; ON_FRONT_ONLY (added
                                // in CameraX 1.3, verified via javap against this project's
                                // 1.5.0 camera-video jar) makes the *saved* recording match
                                // that mirrored preview on a front lens, while leaving a back-
                                // camera recording untouched - it is a no-op unless the bound
                                // camera turns out to be front-facing.
                                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                                .build()

                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                lensFacing.toCameraSelector(),
                                preview,
                                videoCapture,
                            )
                            boundCamera = camera
                            onVideoCaptureReady(videoCapture)
                            onVideoConfigResolved(rangeDecision == VideoDynamicRangeDecision.UseHlg10, effectiveQuality)
                            onCameraReady(camera.cameraHandle(previewView))
                            camera.reapplyPendingZoom(currentDesiredZoomRatio)
                        }
                        CameraMode.Cinematic -> {
                            // Characteristics vary per device, so the config is resolved fresh
                            // on each bind rather than cached alongside the remembered use cases.
                            val cameraInfo = lensFacing.toCameraSelector()
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
                            // requested when the look is Neutral. onVideoConfigResolved below
                            // reports this effective decision, never the raw one, so the overlay
                            // can never claim HLG while the effect actually forces SDR.
                            val rangeDecision = if (look != VideoLook.Neutral) {
                                VideoDynamicRangeDecision.UseSdr
                            } else {
                                rawRangeDecision
                            }
                            val resolvedDynamicRange = if (rangeDecision == VideoDynamicRangeDecision.UseHlg10) {
                                DynamicRange.HLG_10_BIT
                            } else {
                                DynamicRange.SDR
                            }

                            // Quality (issue #72): Cinematic previously pinned Quality.FHD
                            // unconditionally; it now follows the same user choice + device
                            // capability logic as Video mode, queried against this mode's own
                            // resolved dynamic range (HLG and SDR can support different
                            // quality sets on the same camera).
                            val supportedQualities = cameraInfo
                                ?.let { VideoQualityCapabilities.resolve(it, resolvedDynamicRange) }
                                .orEmpty()
                            val effectiveQuality = VideoQualityLogic.resolve(supportedQualities, videoQuality)
                            val recorder = Recorder.Builder()
                                .setQualitySelector(
                                    QualitySelector.from(
                                        effectiveQuality.toCameraXQuality(),
                                        // Belt-and-braces final guard - see the Video-mode
                                        // Recorder above for the same rationale.
                                        FallbackStrategy.higherQualityOrLowerThan(effectiveQuality.toCameraXQuality()),
                                    ),
                                )
                                .build()

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
                                // Mirroring (issue #71): same rationale as the Video-mode
                                // VideoCapture above - matches the front-mirrored preview for
                                // a front-lens recording, no-op on the back lens.
                                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
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
                                    lensFacing.toCameraSelector(),
                                    useCaseGroup,
                                )
                            } else {
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    lensFacing.toCameraSelector(),
                                    preview,
                                    cinematicVideoCapture,
                                )
                            }
                            boundCamera = camera
                            onVideoCaptureReady(cinematicVideoCapture)
                            onCinematicConfigReady(cinematicConfig)
                            onVideoConfigResolved(rangeDecision == VideoDynamicRangeDecision.UseHlg10, effectiveQuality)
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
