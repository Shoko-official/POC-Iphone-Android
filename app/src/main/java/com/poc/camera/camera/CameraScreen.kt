package com.poc.camera.camera

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.R
import com.poc.camera.compare.ComparePair
import com.poc.camera.compare.GuidedCompareStep
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.HdrMergePipeline
import com.poc.camera.pipeline.NightPipeline
import com.poc.camera.pipeline.SuperResolution
import com.poc.camera.settings.CameraSettingsData
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
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

// Tap-to-focus reticle: transient feedback near the tap point, not a control, so it sits
// well under the 48dp minimum touch target. CameraX auto-cancels the metering action
// itself after FOCUS_AUTO_CANCEL_MILLIS; the reticle's own on-screen life
// (FOCUS_RETICLE_SETTLE_DELAY_MILLIS then a fade) is a separate, shorter UI timer.
private const val FOCUS_AUTO_CANCEL_MILLIS = 4_000L
private const val FOCUS_RETICLE_SETTLE_DELAY_MILLIS = 800L
private const val FOCUS_RETICLE_FADE_MILLIS = 250
private val FocusReticleSize = 72.dp
private val FocusReticleCornerRadius = 8.dp
private val FocusReticleStrokeWidth = 2.dp
private val FocusReticleStrokeWidthResolved = 3.dp
private val FocusReticleFocusingColor = Color.White
private val FocusReticleSuccessColor = Color(0xFF7CFF7A)
private val FocusReticleFailureColor = Color(0xFFFF6B57)

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
    // Per-device native-resolution ceiling for burst decode: high-memory devices decode
    // 12 MP+ sensors at native resolution, low-memory devices stay conservative. Read once
    // from the device memory class (a device constant); see BurstImageGeometry.
    val maxBurstPixels = remember(context) {
        val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        BurstImageGeometry.maxBurstPixelsFor(memoryClassMb)
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
    var cameraHandle by remember { mutableStateOf<CameraHandle?>(null) }
    var reticleState by remember { mutableStateOf<FocusReticleState>(FocusReticleState.Idle) }
    var focusRequestSeq by remember { mutableLongStateOf(0L) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // Desired zoom ratio, carried across mode switches (CameraPreview re-applies it, clamped
    // to the new camera's range, on every rebind - see CameraPreview.reapplyPendingZoom).
    var zoomRatio by rememberSaveable { mutableFloatStateOf(1f) }
    var isPinching by remember { mutableStateOf(false) }
    // Mirrors the bound camera's live androidx.camera.core.ZoomState (min/max/current ratio
    // actually in effect), observed via a plain Observer rather than
    // androidx.compose.runtime.livedata - that module isn't a project dependency and adding
    // it for a single LiveData would be a new dependency for one call site.
    var liveZoomState by remember { mutableStateOf<ZoomState?>(null) }
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
    val superResolutionSuccessMessage = stringResource(R.string.burst_super_resolution_success)
    val burstMergeFailureMessage = stringResource(R.string.burst_merge_failure)
    val cinematicFps24Label = stringResource(R.string.cinematic_fps_24)
    val cinematicFpsDefaultLabel = stringResource(R.string.cinematic_fps_default)
    val cinematicStabilizedTemplate = stringResource(R.string.cinematic_overlay_stabilized)
    val cinematicUnstabilizedTemplate = stringResource(R.string.cinematic_overlay_unstabilized)
    val neutralLookLabel = stringResource(R.string.look_neutral)
    val cinematicLookLabel = stringResource(R.string.look_cinematic)
    val recordingIndicatorContentDescription = stringResource(R.string.recording_indicator_content_description)
    val openSettingsContentDescription = stringResource(R.string.open_settings_content_description)
    val tapToFocusContentDescription = stringResource(R.string.tap_to_focus_content_description)
    val zoomResetContentDescriptionTemplate = stringResource(R.string.zoom_reset_content_description)
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

    // Mirrors the bound camera's ZoomState into Compose state. observeForever (rather than
    // observe(lifecycleOwner, ...)) ties this purely to cameraHandle's own lifetime - a fresh
    // handle from a rebind always gets a fresh observer, and the stale one is removed
    // in onDispose rather than relying on the LiveData/LifecycleOwner machinery to do it.
    DisposableEffect(cameraHandle) {
        val handle = cameraHandle
        if (handle == null) {
            onDispose {}
        } else {
            val observer = Observer<ZoomState> { liveZoomState = it }
            handle.cameraInfo.zoomState.observeForever(observer)
            onDispose {
                handle.cameraInfo.zoomState.removeObserver(observer)
                // Bind is tearing down (mode switch/retry/leaving composition); the value
                // is stale until the next handle's observer fires.
                liveZoomState = null
            }
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
            desiredZoomRatio = zoomRatio,
            modifier = Modifier.fillMaxSize(),
            onImageCaptureReady = { imageCapture = it },
            onVideoCaptureReady = { videoCapture = it },
            onBurstControllerReady = { burstController = it },
            onExposureControllerReady = { exposureController = it },
            onCinematicConfigReady = { cinematicConfig = it },
            onCameraReady = { cameraHandle = it },
            onBindError = { previewBindError = true },
        )

        // Tap-to-focus surface: sits above the preview but below every control below it
        // in this Box, so buttons/chips still win the tap in their own bounds. Enabled in
        // every mode (Photo/Video/Cinematic) and while recording - CameraX supports
        // re-metering mid-recording.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it }
                .pointerInput(cameraHandle) {
                    detectTapGestures { tapOffset ->
                        val handle = cameraHandle ?: return@detectTapGestures
                        val bounds = previewSize
                        if (bounds.width <= 0 || bounds.height <= 0) return@detectTapGestures

                        val halfSizePx = FocusReticleSize.toPx() / 2f
                        val point = FocusReticleGeometry.clamp(
                            x = tapOffset.x,
                            y = tapOffset.y,
                            boundsWidth = bounds.width.toFloat(),
                            boundsHeight = bounds.height.toFloat(),
                            halfSize = halfSizePx,
                        )
                        val requestId = ++focusRequestSeq
                        reticleState = FocusReticleReducer.reduce(
                            reticleState,
                            FocusMeteringEvent.TapStarted(point, requestId),
                        )

                        val meteringPoint = handle.meteringPointFactory.createPoint(point.x, point.y)
                        val action = FocusMeteringAction.Builder(
                            meteringPoint,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        )
                            .setAutoCancelDuration(FOCUS_AUTO_CANCEL_MILLIS, TimeUnit.MILLISECONDS)
                            .build()

                        val future = handle.cameraControl.startFocusAndMetering(action)
                        future.addListener(
                            {
                                // A mode switch may tear the bind down before this settles;
                                // the future itself stays safe to read regardless.
                                val succeeded = try {
                                    future.get().isFocusSuccessful
                                } catch (e: Exception) {
                                    false
                                }
                                reticleState = FocusReticleReducer.reduce(
                                    reticleState,
                                    if (succeeded) {
                                        FocusMeteringEvent.MeteringSucceeded(requestId)
                                    } else {
                                        FocusMeteringEvent.MeteringFailed(requestId)
                                    },
                                )
                            },
                            ContextCompat.getMainExecutor(context),
                        )
                    }
                }
                // Pinch-to-zoom as a second, independent pointerInput layer rather than
                // folding it into detectTapGestures above (which only ever sees taps) or
                // reusing the stock detectTransformGestures (which also tracks single-pointer
                // pan/zoom past touch slop - that would intermittently consume and steal a
                // slightly-moved single-finger tap from the detector above). This detector
                // only starts tracking, and only ever consumes pointer changes, once a second
                // pointer is down, so a one-finger tap is always left fully unconsumed for the
                // tap detector's own (default requireUnconsumed = true) awaitFirstDown to see.
                // Verified by reasoning over Compose's consumption model rather than an
                // instrumented/device test - see final report for the residual risk.
                .pointerInput(cameraHandle) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressedChanges = event.changes.filter { it.pressed }
                            if (pressedChanges.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    isPinching = true
                                    val zoomState = liveZoomState
                                    val newRatio = ZoomLogic.computeNewRatio(
                                        current = zoomRatio,
                                        gestureFactor = zoomChange,
                                        min = zoomState?.minZoomRatio ?: 1f,
                                        max = zoomState?.maxZoomRatio ?: 1f,
                                    )
                                    zoomRatio = newRatio
                                    // Fire-and-forget: CameraX coalesces rapid setZoomRatio
                                    // calls, so awaiting each future here would only add
                                    // needless per-frame latency to the gesture.
                                    cameraHandle?.cameraControl?.setZoomRatio(newRatio)
                                    pressedChanges.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        isPinching = false
                    }
                }
                .semantics {
                    contentDescription = tapToFocusContentDescription
                },
        )

        // Drives the pure reducer's Settled event once a resolved (Focused/Failed)
        // reticle has shown long enough; a stale timer from a superseded tap is a no-op
        // in the reducer, so no extra bookkeeping is needed here.
        LaunchedEffect(reticleState) {
            val settleRequestId = when (val state = reticleState) {
                is FocusReticleState.Focused -> state.requestId
                is FocusReticleState.Failed -> state.requestId
                else -> null
            }
            if (settleRequestId != null) {
                delay(FOCUS_RETICLE_SETTLE_DELAY_MILLIS)
                reticleState = FocusReticleReducer.reduce(reticleState, FocusMeteringEvent.Settled(settleRequestId))
            }
        }

        val reticleAlpha by animateFloatAsState(
            targetValue = if (reticleState == FocusReticleState.Idle) 0f else 1f,
            animationSpec = tween(durationMillis = FOCUS_RETICLE_FADE_MILLIS, easing = LinearEasing),
            label = "focus_reticle_alpha",
        )
        // Retains the last non-idle state through the fade-out so the reticle keeps
        // rendering at its resolved point/colour while alpha animates to zero, instead of
        // snapping away the instant the reducer returns to Idle.
        var lastReticleState by remember { mutableStateOf<FocusReticleState?>(null) }
        if (reticleState != FocusReticleState.Idle) {
            lastReticleState = reticleState
        }
        if (reticleAlpha > 0f) {
            lastReticleState?.let { state ->
                FocusReticle(state = state, alpha = reticleAlpha)
            }
        }

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

            if (ZoomLogic.shouldShowChip(zoomRatio, isPinching)) {
                ZoomChip(
                    ratio = zoomRatio,
                    resetContentDescription = String.format(
                        zoomResetContentDescriptionTemplate,
                        ZoomLogic.formatLabel(zoomRatio),
                    ),
                    onReset = {
                        val zoomState = liveZoomState
                        val resetRatio = ZoomLogic.computeNewRatio(
                            current = 1f,
                            gestureFactor = 1f,
                            min = zoomState?.minZoomRatio ?: 1f,
                            max = zoomState?.maxZoomRatio ?: 1f,
                        )
                        zoomRatio = resetRatio
                        cameraHandle?.cameraControl?.setZoomRatio(resetRatio)
                    },
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
                                        BurstImageCapture.capture(capture, burstCaptureExecutor, maxBurstPixels, onResult)
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
                                    } else if (settings.superResolutionEnabled) {
                                        // Standard single-EV burst super-resolved onto a
                                        // doubled grid. Night and HDR are handled above and
                                        // take priority; SR runs only on the plain still path.
                                        controller.arm(frameCapture) { burst ->
                                            Log.d(
                                                TAG,
                                                "SR burst captured ${burst.frames.size} frames " +
                                                    "in ${burst.captureSpanMillis} ms",
                                            )
                                            mergeAndSave {
                                                val result = SuperResolution.superResolve(burst.frames)
                                                result.superResolved to String.format(
                                                    superResolutionSuccessMessage,
                                                    result.usedFrameCount,
                                                    result.superResolved.width,
                                                    result.superResolved.height,
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
 * Current-zoom indicator: an [OverlayChip] that's also a 48dp tap target resetting zoom to
 * 1x (clamped to the device's own floor - see [ZoomLogic]). The visible label is the actual
 * clamped ratio, never an aspirational "1.0x", so a device whose minimum sits above 1x is
 * never misrepresented. Callers gate visibility with [ZoomLogic.shouldShowChip] - this
 * composable itself has no opinion on when it should be shown.
 */
@Composable
private fun ZoomChip(
    ratio: Float,
    resetContentDescription: String,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(OverlayScrimColor, shape = RoundedCornerShape(16.dp))
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onReset)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = resetContentDescription
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ZoomLogic.formatLabel(ratio),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall.copy(shadow = OverlayTextShadow),
        )
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

/**
 * Transient tap-to-focus feedback near the tap point: a small stroked square that reads
 * its colour, stroke weight and inner mark from [state], so success/failure is never
 * colour-only - Focused adds a thicker stroke plus an inner tick, Failed switches to a
 * dashed stroke plus an inner cross. Not a control (see [FocusReticleSize]), so it stays
 * well under the 48dp minimum touch target used elsewhere on this screen.
 */
@Composable
private fun FocusReticle(
    state: FocusReticleState,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val point = when (state) {
        is FocusReticleState.Focusing -> state.point
        is FocusReticleState.Focused -> state.point
        is FocusReticleState.Failed -> state.point
        FocusReticleState.Idle -> return
    }
    val strokeColor = when (state) {
        is FocusReticleState.Focusing -> FocusReticleFocusingColor
        is FocusReticleState.Focused -> FocusReticleSuccessColor
        is FocusReticleState.Failed -> FocusReticleFailureColor
        FocusReticleState.Idle -> return
    }
    val strokeWidth = if (state is FocusReticleState.Focused) {
        FocusReticleStrokeWidthResolved
    } else {
        FocusReticleStrokeWidth
    }
    val dashed = state is FocusReticleState.Failed

    Canvas(
        modifier = modifier
            .offset {
                val halfSizePx = FocusReticleSize.toPx() / 2f
                IntOffset((point.x - halfSizePx).roundToInt(), (point.y - halfSizePx).roundToInt())
            }
            .size(FocusReticleSize)
            .alpha(alpha),
    ) {
        val strokeWidthPx = strokeWidth.toPx()
        val inset = strokeWidthPx / 2f
        val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
            cornerRadius = CornerRadius(FocusReticleCornerRadius.toPx()),
            style = Stroke(width = strokeWidthPx, pathEffect = pathEffect),
        )

        val cx = size.width / 2f
        val cy = size.height / 2f
        when (state) {
            is FocusReticleState.Focused -> {
                // Inner checkmark: the non-colour cue that distinguishes success from a
                // plain thicker stroke alone.
                val tick = size.minDimension * 0.14f
                drawLine(
                    color = strokeColor,
                    start = Offset(cx - tick, cy),
                    end = Offset(cx - tick * 0.2f, cy + tick),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(cx - tick * 0.2f, cy + tick),
                    end = Offset(cx + tick * 1.4f, cy - tick),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
            is FocusReticleState.Failed -> {
                // Inner cross: the non-colour cue that distinguishes failure from the
                // dashed stroke alone.
                val cross = size.minDimension * 0.16f
                drawLine(
                    color = strokeColor,
                    start = Offset(cx - cross, cy - cross),
                    end = Offset(cx + cross, cy + cross),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(cx - cross, cy + cross),
                    end = Offset(cx + cross, cy - cross),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
            else -> Unit
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
