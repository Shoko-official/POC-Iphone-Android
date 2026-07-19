package com.poc.camera.camera

import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.pipeline.Looks
import java.util.concurrent.Executors

private const val TAG = "CameraPreview"
private val BURST_ANALYSIS_RESOLUTION = Size(1280, 720)

@Composable
fun CameraPreview(
    mode: CameraMode,
    modifier: Modifier = Modifier,
    look: VideoLook = VideoLook.Neutral,
    burstFrameCount: Int = BurstController.DEFAULT_FRAME_COUNT,
    retryToken: Int = 0,
    onImageCaptureReady: (ImageCapture) -> Unit = {},
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit = {},
    onBurstControllerReady: (BurstController) -> Unit = {},
    onCinematicConfigReady: (CinematicConfig) -> Unit = {},
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
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val burstController = remember(burstFrameCount) { BurstController(burstFrameCount) }

    // Bind per mode rather than all use cases at once: some devices reject the combined
    // Preview + ImageCapture + VideoCapture graph, so each mode only binds what it needs.
    // retryToken carries no data; bumping it from the caller re-runs a failed bind attempt.
    DisposableEffect(lifecycleOwner, mode, look, burstFrameCount, retryToken) {
        var cameraProvider: ProcessCameraProvider? = null
        var activeEffect: LookCameraEffect? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
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
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                BURST_ANALYSIS_RESOLUTION,
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build(),
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .apply {
                                    setAnalyzer(analysisExecutor) { image -> burstController.onFrame(image) }
                                }

                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                                imageAnalysis,
                            )
                            onImageCaptureReady(imageCapture)
                            onBurstControllerReady(burstController)
                        }
                        CameraMode.Video -> {
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                videoCapture,
                            )
                            onVideoCaptureReady(videoCapture)
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

                            val cinematicVideoCapture = VideoCapture.Builder(recorder)
                                .apply {
                                    if (cinematicConfig.use24Fps) {
                                        val fps = CinematicConfigResolver.TARGET_FPS
                                        setTargetFrameRate(Range(fps, fps))
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

                            if (effect != null) {
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
                            onVideoCaptureReady(cinematicVideoCapture)
                            onCinematicConfigReady(cinematicConfig)
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
            cameraProvider?.unbindAll()
            activeEffect?.release()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}
