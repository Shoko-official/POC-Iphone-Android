package com.poc.camera.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.R
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingParams
import com.poc.camera.pipeline.FinishingPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
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
        CameraPermissionState.Granted -> CameraCaptureScreen(modifier = modifier)
        CameraPermissionState.Denied -> CameraPermissionRationale(
            modifier = modifier,
            onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
        )
        CameraPermissionState.PermanentlyDenied -> CameraPermissionSettingsPrompt(modifier = modifier)
    }
}

@Composable
private fun CameraCaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var burstController by remember { mutableStateOf<BurstController?>(null) }
    var isBurstInProgress by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartMillis by remember { mutableLongStateOf(0L) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var cinematicConfig by remember { mutableStateOf<CinematicConfig?>(null) }
    var videoLook by rememberSaveable { mutableStateOf(VideoLook.Neutral) }

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
    val burstMergeFailureMessage = stringResource(R.string.burst_merge_failure)
    val cinematicFps24Label = stringResource(R.string.cinematic_fps_24)
    val cinematicFpsDefaultLabel = stringResource(R.string.cinematic_fps_default)
    val cinematicStabilizedTemplate = stringResource(R.string.cinematic_overlay_stabilized)
    val cinematicUnstabilizedTemplate = stringResource(R.string.cinematic_overlay_unstabilized)
    val neutralLookLabel = stringResource(R.string.look_neutral)
    val cinematicLookLabel = stringResource(R.string.look_cinematic)

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
            modifier = Modifier.fillMaxSize(),
            onImageCaptureReady = { imageCapture = it },
            onVideoCaptureReady = { videoCapture = it },
            onBurstControllerReady = { burstController = it },
            onCinematicConfigReady = { cinematicConfig = it },
        )

        if (mode == CameraMode.Cinematic) {
            cinematicConfig?.let { config ->
                Text(
                    text = CinematicOverlayText.format(
                        config = config,
                        fps24Label = cinematicFps24Label,
                        fpsDefaultLabel = cinematicFpsDefaultLabel,
                        stabilizedTemplate = cinematicStabilizedTemplate,
                        unstabilizedTemplate = cinematicUnstabilizedTemplate,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isRecording) {
                Text(
                    text = RecordingTimeFormatter.format(elapsedMillis),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (mode == CameraMode.Cinematic) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraModeButton(
                        label = neutralLookLabel,
                        selected = videoLook == VideoLook.Neutral,
                        enabled = !isRecording,
                        onClick = { videoLook = VideoLook.Neutral },
                    )
                    CameraModeButton(
                        label = cinematicLookLabel,
                        selected = videoLook == VideoLook.Cinematic,
                        enabled = !isRecording,
                        onClick = { videoLook = VideoLook.Cinematic },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraModeButton(
                    label = photoModeLabel,
                    selected = mode == CameraMode.Photo,
                    enabled = !isRecording,
                    onClick = { mode = CameraMode.Photo },
                )
                CameraModeButton(
                    label = videoModeLabel,
                    selected = mode == CameraMode.Video,
                    enabled = !isRecording,
                    onClick = { mode = CameraMode.Video },
                )
                CameraModeButton(
                    label = cinematicModeLabel,
                    selected = mode == CameraMode.Cinematic,
                    enabled = !isRecording,
                    onClick = { mode = CameraMode.Cinematic },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mode == CameraMode.Photo) {
                    OutlinedButton(
                        onClick = {
                            val controller = burstController ?: return@OutlinedButton
                            isBurstInProgress = true
                            controller.arm { frames ->
                                // Merge off the main thread, then persist and report.
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val result = BurstMergePipeline.merge(frames)
                                        // Tone/saturation/contrast finishing applies only to the
                                        // merged burst; single PhotoCapture JPEGs are left untouched.
                                        val finished = FinishingPipeline.apply(
                                            result.merged,
                                            FinishingParams.DEFAULT,
                                        )
                                        MergedPhotoSaver.save(context, finished)
                                        withContext(Dispatchers.Main) {
                                            isBurstInProgress = false
                                            snackbarHostState.showSnackbar(
                                                String.format(
                                                    burstMergeSuccessMessage,
                                                    result.usedFrameCount,
                                                ),
                                            )
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isBurstInProgress = false
                                            snackbarHostState.showSnackbar(burstMergeFailureMessage)
                                        }
                                    }
                                }
                            }
                        },
                        enabled = burstController != null && !isBurstInProgress,
                    ) {
                        Text(text = burstButtonLabel)
                    }
                }

                Button(
                    onClick = {
                        if (mode.isVideoLike) {
                            if (isRecording) {
                                activeRecording?.stop()
                            } else {
                                val capture = videoCapture ?: return@Button
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
                            val capture = imageCapture ?: return@Button
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
                    shape = CircleShape,
                    colors = if (isRecording) {
                        ButtonDefaults.buttonColors(containerColor = Color.Red)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .semantics {
                            contentDescription = when {
                                mode.isVideoLike && isRecording -> stopContentDescription
                                mode.isVideoLike -> recordContentDescription
                                else -> shutterContentDescription
                            }
                        },
                ) {}
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun CameraModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) {
            Text(text = label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(text = label)
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
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRequestPermission) {
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
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = { context.startActivity(context.appSettingsIntent()) }) {
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
