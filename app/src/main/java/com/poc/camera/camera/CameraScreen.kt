package com.poc.camera.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.R
import com.poc.camera.compare.ComparePair
import com.poc.camera.compare.GuidedCompareStep
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.HdrMergePipeline
import com.poc.camera.pipeline.NightPipeline
import com.poc.camera.settings.CameraSettingsData
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Shared scrim strong enough to keep white overlay text/icons legible over a bright,
// unpredictable live preview (sky, snow, direct light); paired with a text shadow below
// for the worst case of a near-white background behind a status chip.
private const val TAG = "CameraScreen"
private val OverlayScrimColor = Color.Black.copy(alpha = 0.6f)
private val OverlayTextShadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 4f)
private val SecondaryControlSlotWidth = 88.dp

@Composable
fun CameraScreen(
    settings: CameraSettingsData,
    onOpenSettings: () -> Unit,
    comparePair: ComparePair?,
    onOpenCompare: () -> Unit,
    onComparePairCaptured: (ComparePair) -> Unit,
    modifier: Modifier = Modifier,
    guidedStep: GuidedCompareStep = GuidedCompareStep.Idle,
    onGuidedCaptureCompleted: () -> Unit = {},
    onGuidedCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    var permissionState by rememberSaveable {
        mutableStateOf(context.currentCameraPermissionState())
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        permissionState = CameraPermissionEvaluator.evaluate(
            isGranted = isGranted,
            hasRequestedBefore = true,
            shouldShowRationale = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                ?: false,
        )
    }

    LaunchedEffect(Unit) {
        if (permissionState != CameraPermissionState.Granted) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Permission may be granted from system settings while the app is backgrounded.
            if (event == Lifecycle.Event.ON_RESUME &&
                context.currentCameraPermissionState() == CameraPermissionState.Granted
            ) {
                permissionState = CameraPermissionState.Granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (permissionState) {
        CameraPermissionState.Granted -> CameraCaptureScreen(
            settings = settings,
            onOpenSettings = onOpenSettings,
            comparePair = comparePair,
            onOpenCompare = onOpenCompare,
            onComparePairCaptured = onComparePairCaptured,
            guidedStep = guidedStep,
            onGuidedCaptureCompleted = onGuidedCaptureCompleted,
            onGuidedCancel = onGuidedCancel,
            modifier = modifier,
        )
        CameraPermissionState.Denied -> CameraPermissionRationale(
            modifier = modifier,
            onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
        )
        CameraPermissionState.PermanentlyDenied -> CameraPermissionSettingsPrompt(modifier = modifier)
    }
}

@Composable
private fun CameraCaptureScreen(
    settings: CameraSettingsData,
    onOpenSettings: () -> Unit,
    comparePair: ComparePair?,
    onOpenCompare: () -> Unit,
    onComparePairCaptured: (ComparePair) -> Unit,
    guidedStep: GuidedCompareStep,
    onGuidedCaptureCompleted: () -> Unit,
    onGuidedCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Single background thread that both delivers each takePicture callback and decodes
    // the returned JPEG, so heavy full-resolution decode stays off the main thread and
    // captures never overlap. Shut down when the screen leaves composition.
    val burstCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { burstCaptureExecutor.shutdown() }
    }
    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var burstController by remember { mutableStateOf<BurstController?>(null) }
    var exposureController by remember { mutableStateOf<ExposureController?>(null) }
    var isBurstInProgress by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartMillis by remember { mutableLongStateOf(0L) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var cinematicConfig by remember { mutableStateOf<CinematicConfig?>(null) }
    // Settings only seed the initial look for a fresh session; once the user picks a
    // look in Cinematic mode it stays under their control for the rest of the session.
    var videoLook by rememberSaveable { mutableStateOf(settings.defaultCinematicLook) }
    var previewBindError by remember { mutableStateOf(false) }
    var previewRetryToken by remember { mutableIntStateOf(0) }

    val captureSuccessMessage = stringResource(R.string.capture_success)
    val captureFailureMessage = stringResource(R.string.capture_failure)
    val videoSuccessMessage = stringResource(R.string.video_capture_success)
    val videoFailureMessage = stringResource(R.string.video_capture_failure)
    val shutterContentDescription = stringResource(R.string.shutter_button_content_description)
    val recordContentDescription = stringResource(R.string.record_button_content_description)
    val stopContentDescription = stringResource(R.string.stop_button_content_description)
    val photoModeLabel = stringResource(R.string.mode_photo)
    val videoModeLabel = stringResource(R.string.mode_video)
    val cinematicModeLabel = stringResource(R.string.mode_cinematic)
    val burstButtonLabel = stringResource(R.string.burst_button)
    val burstMergeSuccessMessage = stringResource(R.string.burst_merge_success)
    val hdrBurstMergeSuccessMessage = stringResource(R.string.burst_hdr_merge_success)
    val burstMergeFailureMessage = stringResource(R.string.burst_merge_failure)
    val cinematicFps24Label = stringResource(R.string.cinematic_fps_24)
    val cinematicFpsDefaultLabel = stringResource(R.string.cinematic_fps_default)
    val cinematicStabilizedTemplate = stringResource(R.string.cinematic_overlay_stabilized)
    val cinematicUnstabilizedTemplate = stringResource(R.string.cinematic_overlay_unstabilized)
    val neutralLookLabel = stringResource(R.string.look_neutral)
    val cinematicLookLabel = stringResource(R.string.look_cinematic)
    val recordingIndicatorContentDescription = stringResource(R.string.recording_indicator_content_description)
    val openSettingsContentDescription = stringResource(R.string.open_settings_content_description)
    val compareActionLabel = stringResource(R.string.compare_action)
    val previewBindFailureMessage = stringResource(R.string.preview_bind_failure_message)
    val previewRetryLabel = stringResource(R.string.preview_bind_retry)
    val guidedCaptureBannerMessage = stringResource(R.string.guided_comparison_camera_banner)
    val guidedCaptureBannerDetail = stringResource(R.string.guided_comparison_camera_banner_detail)
    val guidedCancelContentDescription = stringResource(R.string.guided_comparison_cancel_content_description)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Recording proceeds without audio if this was denied; checked again at record time. */ }

    LaunchedEffect(mode) {
        if (mode.isVideoLike &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(isRecording, recordingStartMillis) {
        while (isRecording) {
            elapsedMillis = System.currentTimeMillis() - recordingStartMillis
            delay(200)
        }
    }

    // Switching mode rebinds the camera use cases, which would orphan an in-flight
    // recording, so stop it whenever the mode changes or the screen leaves composition.
    DisposableEffect(mode) {
        if (mode != CameraMode.Cinematic) {
            cinematicConfig = null
        }
        onDispose {
            activeRecording?.stop()
        }
    }

    Box(modifier = modifier) {
        CameraPreview(
            mode = mode,
            look = videoLook,
            // Night mode always captures a longer burst, regardless of the user's normal
            // frame-count preference, since low light needs the extra frames.
            burstFrameCount = if (settings.nightModeEnabled) {
                NightPipeline.NIGHT_BURST_FRAME_COUNT
            } else {
                settings.burstFrameCount
            },
            retryToken = previewRetryToken,
            modifier = Modifier.fillMaxSize(),
            onImageCaptureReady = { imageCapture = it },
            onVideoCaptureReady = { videoCapture = it },
            onBurstControllerReady = { burstController = it },
            onExposureControllerReady = { exposureController = it },
            onCinematicConfigReady = { cinematicConfig = it },
            onBindError = { previewBindError = true },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (guidedStep == GuidedCompareStep.AwaitingCapture) {
                GuidedCaptureBanner(
                    message = guidedCaptureBannerMessage,
                    detail = guidedCaptureBannerDetail,
                    cancelContentDescription = guidedCancelContentDescription,
                    onCancel = onGuidedCancel,
                )
            }

            if (mode == CameraMode.Cinematic) {
                cinematicConfig?.let { config ->
                    OverlayChip {
                        Text(
                            text = CinematicOverlayText.format(
                                config = config,
                                fps24Label = cinematicFps24Label,
                                fpsDefaultLabel = cinematicFpsDefaultLabel,
                                stabilizedTemplate = cinematicStabilizedTemplate,
                                unstabilizedTemplate = cinematicUnstabilizedTemplate,
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(shadow = OverlayTextShadow),
                        )
                    }
                }
            }

            if (isRecording) {
                OverlayChip {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = recordingIndicatorContentDescription
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.Red, CircleShape),
                        )
                        Text(
                            text = RecordingTimeFormatter.format(elapsedMillis),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge.copy(shadow = OverlayTextShadow),
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onOpenSettings,
            enabled = !isRecording,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(OverlayScrimColor, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = openSettingsContentDescription,
                tint = Color.White.copy(alpha = if (isRecording) 0.38f else 1f),
            )
        }

        // Only offered once this session has actually produced a processed/reference
        // pair (i.e. "Save comparison pair" was on for at least one burst).
        if (comparePair != null) {
            Button(
                onClick = onOpenCompare,
                enabled = !isRecording,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(text = compareActionLabel)
            }
        }

        if (previewBindError) {
            OverlayChip(modifier = Modifier.align(Alignment.Center)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = previewBindFailureMessage,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = OverlayTextShadow),
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = {
                            previewBindError = false
                            previewRetryToken++
                        },
                    ) {
                        Text(text = previewRetryLabel)
                    }
                }
            }
        }

        OverlayChip(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ModeSelector(
                    selected = mode,
                    enabled = !isRecording,
                    photoLabel = photoModeLabel,
                    videoLabel = videoModeLabel,
                    cinematicLabel = cinematicModeLabel,
                    onSelected = { mode = it },
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(SecondaryControlSlotWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mode == CameraMode.Photo) {
                            // Merge (single-EV or HDR) off the main thread, then finish,
                            // persist and report; shared by both burst paths.
                            val mergeAndSave: (suspend () -> Pair<Frame, String>) -> Unit = { produce ->
                                // Snapshot once at merge start: if the user dismisses the guided
                                // banner mid-burst, this capture finishes as a normal one.
                                val guidedCaptureActive = guidedStep == GuidedCompareStep.AwaitingCapture
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val (merged, message) = produce()
                                        // Finishing (tone/saturation/contrast) is optional and
                                        // only ever applies to the merged burst frame.
                                        // Night captures use the night finishing profile,
                                        // regardless of preset; every other merge (standard or
                                        // HDR) uses the user's chosen rendition preset.
                                        val finishingParams = if (settings.nightModeEnabled) {
                                            NightPipeline.FINISHING_PARAMS
                                        } else {
                                            settings.finishingPreset.params
                                        }
                                        val output = if (settings.applyFinishingToMergedPhotos) {
                                            FinishingPipeline.apply(merged, finishingParams)
                                        } else {
                                            merged
                                        }
                                        val processedUri = MergedPhotoSaver.save(context, output)
                                        // A guided-comparison capture always fills slot A with the
                                        // processed result and leaves slot B unset until the user
                                        // picks the reference photo on the Compare screen. Otherwise,
                                        // persist the unprocessed merge input, as-is, for on-device
                                        // A/B comparison when the user opted in via settings.
                                        val capturedPair = if (guidedCaptureActive) {
                                            ComparePair(processedUri = processedUri, referenceUri = null)
                                        } else if (settings.saveComparisonPair) {
                                            val referenceUri = MergedPhotoSaver.save(
                                                context,
                                                merged,
                                                prefix = MergedPhotoSaver.RAW_PREFIX,
                                            )
                                            ComparePair(processedUri = processedUri, referenceUri = referenceUri)
                                        } else {
                                            null
                                        }
                                        withContext(Dispatchers.Main) {
                                            isBurstInProgress = false
                                            if (guidedCaptureActive && capturedPair != null) {
                                                onComparePairCaptured(capturedPair)
                                                onGuidedCaptureCompleted()
                                            } else if (capturedPair != null) {
                                                onComparePairCaptured(capturedPair)
                                                val result = snackbarHostState.showSnackbar(
                                                    message = message,
                                                    actionLabel = compareActionLabel,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    onOpenCompare()
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar(message)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isBurstInProgress = false
                                            snackbarHostState.showSnackbar(burstMergeFailureMessage)
                                        }
                                    }
                                }
                            }
                            val controllerReady = burstController != null
                            Button(
                                onClick = {
                                    val controller = burstController ?: return@Button
                                    val capture = imageCapture ?: return@Button
                                    isBurstInProgress = true
                                    val frameCapture = BurstController.FrameCapture { onResult ->
                                        BurstImageCapture.capture(capture, burstCaptureExecutor, onResult)
                                    }
                                    val exposure = exposureController
                                    if (settings.nightModeEnabled) {
                                        // Night mode takes priority over HDR: a single-EV
                                        // long burst merged with the night profile. When both
                                        // switches are on, HDR is skipped for this capture and
                                        // the standard merge snackbar reflects that night ran.
                                        controller.arm(frameCapture) { burst ->
                                            Log.d(
                                                TAG,
                                                "Night burst captured ${burst.frames.size} frames " +
                                                    "in ${burst.captureSpanMillis} ms",
                                            )
                                            mergeAndSave {
                                                val result = NightPipeline.merge(burst.frames)
                                                result.merged to String.format(
                                                    burstMergeSuccessMessage,
                                                    result.usedFrameCount,
                                                    result.merged.width,
                                                    result.merged.height,
                                                )
                                            }
                                        }
                                    } else if (settings.hdrBurstEnabled && exposure != null) {
                                        controller.armBracketed(
                                            steps = exposure.steps,
                                            framesPerEv = exposure.framesPerEv,
                                            setExposureIndex = exposure.setIndexAndAwait,
                                            captureFrame = frameCapture,
                                        ) { burst ->
                                            Log.d(
                                                TAG,
                                                "HDR burst captured ${burst.frames.size} frames " +
                                                    "in ${burst.captureSpanMillis} ms",
                                            )
                                            mergeAndSave {
                                                val result = HdrMergePipeline.merge(burst.frames, burst.evs)
                                                result.fused to String.format(
                                                    hdrBurstMergeSuccessMessage,
                                                    result.usedFrameCount,
                                                    result.fused.width,
                                                    result.fused.height,
                                                )
                                            }
                                        }
                                    } else {
                                        controller.arm(frameCapture) { burst ->
                                            Log.d(
                                                TAG,
                                                "Burst captured ${burst.frames.size} frames " +
                                                    "in ${burst.captureSpanMillis} ms",
                                            )
                                            mergeAndSave {
                                                val result = BurstMergePipeline.merge(burst.frames)
                                                result.merged to String.format(
                                                    burstMergeSuccessMessage,
                                                    result.usedFrameCount,
                                                    result.merged.width,
                                                    result.merged.height,
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = controllerReady && !isBurstInProgress,
                            ) {
                                Text(text = burstButtonLabel)
                            }
                        }
                    }

                    ShutterButton(
                        mode = mode,
                        isRecording = isRecording,
                        contentDescription = when {
                            mode.isVideoLike && isRecording -> stopContentDescription
                            mode.isVideoLike -> recordContentDescription
                            else -> shutterContentDescription
                        },
                        onClick = {
                            if (mode.isVideoLike) {
                                if (isRecording) {
                                    activeRecording?.stop()
                                } else {
                                    val capture = videoCapture ?: return@ShutterButton
                                    activeRecording = VideoRecording.start(
                                        context = context,
                                        recorder = capture.output,
                                        onFinalized = { event ->
                                            isRecording = false
                                            activeRecording = null
                                            if (event.hasError()) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(videoFailureMessage)
                                                }
                                            } else {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        String.format(
                                                            videoSuccessMessage,
                                                            event.outputResults.outputUri,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    recordingStartMillis = System.currentTimeMillis()
                                    elapsedMillis = 0L
                                    isRecording = true
                                }
                            } else {
                                val capture = imageCapture ?: return@ShutterButton
                                PhotoCapture.capture(
                                    context = context,
                                    imageCapture = capture,
                                    onSuccess = { uri ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                String.format(captureSuccessMessage, uri),
                                            )
                                        }
                                    },
                                    onError = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(captureFailureMessage)
                                        }
                                    },
                                )
                            }
                        },
                    )

                    Box(
                        modifier = Modifier.width(SecondaryControlSlotWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mode == CameraMode.Cinematic) {
                            LookSelector(
                                selected = videoLook,
                                enabled = !isRecording,
                                neutralLabel = neutralLookLabel,
                                cinematicLabel = cinematicLookLabel,
                                onSelected = { videoLook = it },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Rounded translucent backing so white overlay content stays legible over the live preview. */
@Composable
private fun OverlayChip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(OverlayScrimColor, shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/**
 * Step-1 instruction for the guided comparison flow: shown while the user is back on
 * Camera to capture the shot that will fill slot A. [onCancel] resets the whole guided
 * flow (see [GuidedCompareFlow.advance]) rather than just hiding this banner, so the
 * next burst goes back to behaving like a normal capture.
 */
@Composable
private fun GuidedCaptureBanner(
    message: String,
    detail: String,
    cancelContentDescription: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayChip(modifier = modifier.widthIn(max = 320.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge.copy(shadow = OverlayTextShadow),
                )
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall.copy(shadow = OverlayTextShadow),
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = cancelContentDescription,
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selected: CameraMode,
    enabled: Boolean,
    photoLabel: String,
    videoLabel: String,
    cinematicLabel: String,
    onSelected: (CameraMode) -> Unit,
) {
    val options = listOf(
        CameraMode.Photo to photoLabel,
        CameraMode.Video to videoLabel,
        CameraMode.Cinematic to cinematicLabel,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 320.dp)) {
        options.forEachIndexed { index, (candidateMode, label) ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == candidateMode,
                enabled = enabled,
                onClick = { onSelected(candidateMode) },
                icon = {},
                label = { Text(text = label) },
            )
        }
    }
}

@Composable
private fun LookSelector(
    selected: VideoLook,
    enabled: Boolean,
    neutralLabel: String,
    cinematicLabel: String,
    onSelected: (VideoLook) -> Unit,
) {
    val options = listOf(
        VideoLook.Neutral to neutralLabel,
        VideoLook.Cinematic to cinematicLabel,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = SecondaryControlSlotWidth * 2)) {
        options.forEachIndexed { index, (candidateLook, label) ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == candidateLook,
                enabled = enabled,
                onClick = { onSelected(candidateLook) },
                icon = {},
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/**
 * Single unified shutter affordance: a white ring that stays constant while its centre
 * swaps shape - filled white disc for photo, red disc for an idle video-like mode, red
 * square once recording - rather than swapping to a different control per mode.
 */
@Composable
private fun ShutterButton(
    mode: CameraMode,
    isRecording: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            mode.isVideoLike && isRecording -> Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Red, RoundedCornerShape(6.dp)),
            )
            mode.isVideoLike -> Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color.Red, CircleShape),
            )
            else -> Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_rationale),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.camera_permission_grant))
        }
    }
}

@Composable
private fun CameraPermissionSettingsPrompt(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_permanently_denied),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { context.startActivity(context.appSettingsIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.camera_permission_open_settings))
        }
    }
}

private fun Context.currentCameraPermissionState(): CameraPermissionState =
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        CameraPermissionState.Granted
    } else {
        CameraPermissionState.Denied
    }

private fun Context.appSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
