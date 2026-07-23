package com.poc.camera.camera

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poc.camera.R
import com.poc.camera.compare.ComparePair
import com.poc.camera.compare.GuidedCompareStep
import com.poc.camera.compare.ReferenceImageLoader
import com.poc.camera.pipeline.BokehParams
import com.poc.camera.pipeline.BokehRenderer
import com.poc.camera.pipeline.BurstMergePipeline
import com.poc.camera.pipeline.FinishingPipeline
import com.poc.camera.pipeline.Frame
import com.poc.camera.pipeline.HdrMergePipeline
import com.poc.camera.pipeline.NightPipeline
import com.poc.camera.pipeline.SuperResolution
import com.poc.camera.pipeline.mirrorHorizontal
import com.poc.camera.settings.CameraSettingsData
import com.poc.camera.settings.VideoQualityLogic
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Volume-key shutter and capture haptics (issue #73): deliberately no MediaActionSound -
// several regions (e.g. Japan/Korea) and OEMs already enforce an audible shutter sound at
// the OS/hardware level regardless of what an app does, so an app-level sound would either
// be redundant or fight a platform policy this POC has no business overriding. The haptic
// pulse on capture/record events is the only added feedback.

// Shared scrim strong enough to keep white overlay text/icons legible over a bright,
// unpredictable live preview (sky, snow, direct light); paired with a text shadow below
// for the worst case of a near-white background behind a status chip.
private const val TAG = "CameraScreen"
// The chrome's scrim and text shadow now live once in ChromeTokens (ViewfinderChrome.kt);
// these aliases keep the remaining message-surface composables (OverlayChip, the guided
// banner, the bind-error card) reading from that single source of truth.
private val OverlayScrimColor = ChromeTokens.Scrim
private val OverlayTextShadow = ChromeTokens.TextShadow
private val SecondaryControlSlotWidth = 88.dp

// Tap-to-focus reticle: transient feedback near the tap point, not a control, so it sits
// well under the 48dp minimum touch target. CameraX auto-cancels the metering action
// itself after FOCUS_AUTO_CANCEL_MILLIS; the reticle's own on-screen life
// (FOCUS_RETICLE_SETTLE_DELAY_MILLIS then a fade) is a separate, shorter UI timer.
private const val FOCUS_AUTO_CANCEL_MILLIS = 4_000L
private const val FOCUS_RETICLE_SETTLE_DELAY_MILLIS = 800L
private const val FOCUS_RETICLE_FADE_MILLIS = 250
private const val FOCUS_RETICLE_SCALE_MILLIS = 180
private val FocusReticleSize = 72.dp
private val FocusReticleStrokeWidth = 2.dp
private val FocusReticleCornerArm = 16.dp
// Redesigned reticle (issue #82): four L-shaped corner brackets that read their state through
// geometry and brightness, never colour alone - a subtler photographic convention than the
// old check/cross glyphs. Focus lock contracts the brackets and brightens to full white; a
// failure pushes them apart and dims them.
private const val FOCUS_RETICLE_SCALE_FOCUSING = 1f
private const val FOCUS_RETICLE_SCALE_FOCUSED = 0.82f
private const val FOCUS_RETICLE_SCALE_FAILED = 1.18f
private const val FOCUS_RETICLE_ALPHA_FOCUSING = 0.9f
private const val FOCUS_RETICLE_ALPHA_FOCUSED = 1f
private const val FOCUS_RETICLE_ALPHA_FAILED = 0.5f

// Last-capture thumbnail (issue #74): ~56dp visual size doubles as the touch target
// (already clears the 48dp minimum used elsewhere on this screen, e.g. ZoomChip), so no
// extra padding is needed just to satisfy touch-target size. The decode target is smaller
// than the visual size to keep the async load cheap - it only ever needs to fill a 56dp
// tile, never a full-screen preview like ReferenceImageLoader's other call site in
// CompareScreen.
private val LastCaptureThumbnailSize = 56.dp
private val LastCaptureThumbnailCornerRadius = 12.dp
private const val LAST_CAPTURE_THUMBNAIL_DECODE_PX = 112

/** Round-trips [LastCapture] through its URI and media type as strings, the same pattern
 * MainActivity's ComparePairSaver uses for ComparePair, so the thumbnail survives a
 * rotation without being written to disk - see [LastCapture]'s own doc for why. */
private val LastCaptureSaver: Saver<LastCapture?, List<String>> = Saver(
    save = { capture ->
        if (capture == null) {
            emptyList()
        } else {
            listOf(capture.uri.toString(), capture.mediaType.name)
        }
    },
    restore = { saved ->
        // Defensive (issue #143): a stale or foreign persisted value can carry a media-type
        // string that no longer maps to a CaptureMediaType constant. rememberSaveable does
        // not catch restore-time exceptions, so a bare valueOf would crash the whole restore;
        // fall back to "no last capture" instead, mirroring ComparePairSaver's tolerant style.
        if (saved.size == 2) {
            runCatching { CaptureMediaType.valueOf(saved[1]) }.getOrNull()?.let {
                LastCapture(uri = saved[0].toUri(), mediaType = it)
            }
        } else {
            null
        }
    },
)

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
    // Hardware volume-key shutter (issue #73): incremented once per accepted press by
    // MainActivity.onKeyDown, which lives outside this composition - see VolumeShutterPolicy.
    // 0 is the sentinel "no press yet" value.
    volumeShutterTrigger: Int = 0,
    // MainActivity.onKeyDown needs to know whether camera permission is currently granted
    // to gate the volume shutter the same way this screen itself does below, but that
    // Activity-level dispatch happens outside this composition - see VolumeShutterPolicy.
    onCameraPermissionStateChanged: (CameraPermissionState) -> Unit = {},
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

    // Mirrors permissionState out to MainActivity so its onKeyDown (outside this
    // composition) can gate the volume shutter the same way this screen gates its own UI.
    LaunchedEffect(permissionState) {
        onCameraPermissionStateChanged(permissionState)
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
            volumeShutterTrigger = volumeShutterTrigger,
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
    volumeShutterTrigger: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Background thread that delivers each takePicture callback and copies the JPEG bytes
    // off the ImageProxy (BurstImageCapture.acquire) - fast, so the camera session frees up
    // for the next capture almost immediately. Shut down when the screen leaves composition.
    val burstCaptureExecutor = remember { Executors.newSingleThreadExecutor() }
    // Separate single-threaded executor for the slow half: decoding acquired JPEG bytes into
    // a pipeline Frame (BurstImageCapture.decode). Split from burstCaptureExecutor (issue
    // #87) so decode of frame N runs concurrently with acquisition of frame N+1 instead of
    // blocking it - see BurstController's "decode pipelining" doc. Single-threaded, not a
    // pool: BurstController relies on decodes settling in capture order and its
    // CaptureSpans.Builder being driven from one thread only.
    val burstDecodeExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            burstCaptureExecutor.shutdown()
            burstDecodeExecutor.shutdown()
        }
    }
    // Per-device native-resolution ceiling for burst decode: high-memory devices decode
    // 12 MP+ sensors at native resolution, low-memory devices stay conservative. Read once
    // from the device memory class (a device constant); see BurstImageGeometry.
    val maxBurstPixels = remember(context) {
        val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        BurstImageGeometry.maxBurstPixelsFor(memoryClassMb)
    }
    var mode by rememberSaveable { mutableStateOf(CameraMode.Photo) }
    // Which physical camera is bound (issue #71); survives mode switches and process death
    // the same way zoomRatio/flashMode do below - it is a deliberate user choice, not
    // session-only state like torch.
    var lensFacing by rememberSaveable { mutableStateOf(LensFacing.Back) }
    // Capability gate for the switch-camera chip - see CameraPreview's onCameraAvailability.
    // Both default to false (rather than assuming a back camera exists) so the chip never
    // flashes visible then disappears before the provider has actually reported in.
    var hasBackCamera by remember { mutableStateOf(false) }
    var hasFrontCamera by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var burstController by remember { mutableStateOf<BurstController?>(null) }
    var exposureController by remember { mutableStateOf<ExposureController?>(null) }
    // Capture/processing queue (issue #86): isCapturing mirrors BurstController.isArmed - the
    // camera hardware is busy, so the shutter/volume key MUST stay disabled (see
    // ProcessingQueuePolicy.canCapture). processingCount is decoupled from that: it counts
    // merge/segment/finish/save jobs running on Dispatchers.Default, so once a burst's frames
    // are collected (BurstController's completion callback), a new capture can be armed
    // immediately while the previous one keeps processing in the background - bounded to
    // ProcessingQueuePolicy.DEFAULT_MAX_CONCURRENT_PROCESSING as a memory bound, not a UX
    // restriction (see that constant's doc). currentProcessingStage is one shared var every
    // in-flight job overwrites at its own phase boundaries, so it always reads as the MOST
    // RECENT job's stage with no per-job breakdown.
    var isCapturing by remember { mutableStateOf(false) }
    var processingCount by remember { mutableIntStateOf(0) }
    var currentProcessingStage by remember { mutableStateOf<ProcessingStage?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartMillis by remember { mutableLongStateOf(0L) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var cinematicConfig by remember { mutableStateOf<CinematicConfig?>(null) }
    // Whether the just-bound Video/Cinematic use case actually resolved to HLG10 - see
    // CameraPreview.onVideoConfigResolved and VideoDynamicRangeResolver. Reset alongside
    // cinematicConfig below so a stale reading never survives a switch to Photo.
    var isHlgActive by remember { mutableStateOf(false) }
    // Effective video quality the just-bound Video/Cinematic use case actually resolved to
    // (issue #72) - see CameraPreview.onVideoConfigResolved and VideoQualityLogic. Seeded
    // from settings.videoQuality (never null) so the "differs from the user's choice" check
    // below is trivially false before the first bind reports in, rather than showing a
    // spurious mismatch chip.
    var effectiveVideoQuality by remember { mutableStateOf(settings.videoQuality) }
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
    // Persists across mode switches like zoomRatio - cycling flash is a deliberate user
    // choice that should survive a Photo -> Video -> Photo round trip, not reset silently.
    var flashMode by rememberSaveable { mutableStateOf(FlashMode.Off) }
    // Session-only (not rememberSaveable): torch is a physical light, not a preference worth
    // restoring after process death, and TorchLogic.torchEnabledAfterModeChange already resets
    // it to false on every transition out of a video-like mode - see the LaunchedEffect below.
    var torchEnabled by remember { mutableStateOf(false) }
    // Mirrors the bound camera's live androidx.camera.core.TorchState the same way
    // liveZoomState mirrors ZoomState (see the DisposableEffect below) so the torch chip
    // reflects what the camera actually reports - e.g. if it auto-disables the torch itself.
    var liveTorchState by remember { mutableStateOf<Int?>(null) }
    // Settings only seed the initial look for a fresh session; once the user picks a
    // look in Cinematic mode it stays under their control for the rest of the session.
    var videoLook by rememberSaveable { mutableStateOf(settings.defaultCinematicLook) }
    var previewBindError by remember { mutableStateOf(false) }
    var previewRetryToken by remember { mutableIntStateOf(0) }
    // Last-capture thumbnail (issue #74): set on every successful capture path below
    // (single photo, merged burst/HDR/night/SR, video finalize) - session-scoped only,
    // see LastCapture's doc for why this is rememberSaveable rather than persisted.
    var lastCapture by rememberSaveable(stateSaver = LastCaptureSaver) {
        mutableStateOf<LastCapture?>(null)
    }

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
    val portraitModeLabel = stringResource(R.string.mode_portrait)
    val burstMergeSuccessMessage = stringResource(R.string.burst_merge_success)
    val hdrBurstMergeSuccessMessage = stringResource(R.string.burst_hdr_merge_success)
    val superResolutionSuccessMessage = stringResource(R.string.burst_super_resolution_success)
    val burstMergeFailureMessage = stringResource(R.string.burst_merge_failure)
    val portraitMergeSuccessMessage = stringResource(R.string.portrait_merge_success)
    val portraitNoSubjectFoundMessage = stringResource(R.string.portrait_no_subject_found)
    val cinematicFps24Label = stringResource(R.string.cinematic_fps_24)
    val cinematicFpsDefaultLabel = stringResource(R.string.cinematic_fps_default)
    val cinematicStabilizedTemplate = stringResource(R.string.cinematic_overlay_stabilized)
    val cinematicUnstabilizedTemplate = stringResource(R.string.cinematic_overlay_unstabilized)
    val cinematicRangeSuffixTemplate = stringResource(R.string.cinematic_overlay_range_suffix)
    val videoHdrStatusHlg10Label = stringResource(R.string.video_hdr_status_hlg10)
    val videoHdrStatusSdrLabel = stringResource(R.string.video_hdr_status_sdr)
    val videoQualityMismatchContentDescriptionTemplate =
        stringResource(R.string.video_quality_mismatch_content_description)
    val neutralLookLabel = stringResource(R.string.look_neutral)
    // Short form for the tiny label under the Look toggle (issue #82); the full
    // "Cinematic look" is kept for the Settings default-look picker.
    val cinematicLookShortLabel = stringResource(R.string.look_cinematic_short)
    val lookToggleContentDescriptionTemplate = stringResource(R.string.look_toggle_content_description)
    val recordingIndicatorContentDescription = stringResource(R.string.recording_indicator_content_description)
    val openSettingsContentDescription = stringResource(R.string.open_settings_content_description)
    val tapToFocusContentDescription = stringResource(R.string.tap_to_focus_content_description)
    val zoomResetContentDescriptionTemplate = stringResource(R.string.zoom_reset_content_description)
    // Flash/torch are icon buttons now (issue #82): the distinct flash glyphs and the torch
    // button's inverted active state carry the non-colour state cue, so only the spoken
    // content descriptions remain - the old text-chip labels are gone.
    val flashContentDescriptionOff = stringResource(R.string.flash_control_content_description_off)
    val flashContentDescriptionAuto = stringResource(R.string.flash_control_content_description_auto)
    val flashContentDescriptionOn = stringResource(R.string.flash_control_content_description_on)
    val torchContentDescriptionOff = stringResource(R.string.torch_control_content_description_off)
    val torchContentDescriptionOn = stringResource(R.string.torch_control_content_description_on)
    val switchCameraContentDescription = stringResource(R.string.switch_camera_content_description)
    val compareActionLabel = stringResource(R.string.compare_action)
    val previewBindFailureMessage = stringResource(R.string.preview_bind_failure_message)
    val previewRetryLabel = stringResource(R.string.preview_bind_retry)
    val guidedCaptureBannerMessage = stringResource(R.string.guided_comparison_camera_banner)
    val guidedCaptureBannerDetail = stringResource(R.string.guided_comparison_camera_banner_detail)
    val guidedCancelContentDescription = stringResource(R.string.guided_comparison_cancel_content_description)
    val lastCaptureContentDescription = stringResource(R.string.last_capture_content_description)
    val lastCaptureOpenFailureMessage = stringResource(R.string.last_capture_open_failure)
    // Processing queue (issue #86): the stage labels a ProcessingStage maps to, plus the
    // "xN" count-suffix template - all resolved once here and handed to the pure
    // ProcessingQueuePolicy functions, the same pattern CinematicOverlayText's callers use.
    val processingStageCapturingLabel = stringResource(R.string.processing_stage_capturing)
    val processingStageMergingLabel = stringResource(R.string.processing_stage_merging)
    val processingStageFinishingLabel = stringResource(R.string.processing_stage_finishing)
    val processingStageSavingLabel = stringResource(R.string.processing_stage_saving)
    val processingStageCountSuffixTemplate = stringResource(R.string.processing_stage_count_suffix)

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

    // Torch has no control surface outside Video/Cinematic - see TorchLogic for the reset
    // rule. The actual CameraControl.enableTorch call is driven by torchEnabled below, once
    // this has settled it (and, independently, by CameraPreview's own onDispose safety net).
    LaunchedEffect(mode) {
        torchEnabled = TorchLogic.torchEnabledAfterModeChange(mode, torchEnabled)
    }

    // Applies the photo-mode flash cycle to the actual ImageCapture instance whenever either
    // changes: a fresh bind (imageCapture) or the user cycling the top-status control
    // (flashMode). CameraPreview also applies it once at bind time so a freshly (re)bound
    // ImageCapture never starts from FLASH_MODE_OFF regardless of ordering.
    LaunchedEffect(imageCapture, flashMode) {
        imageCapture?.flashMode = flashMode.toImageCaptureFlashMode()
    }

    // Applies the requested torch state to the actual CameraControl whenever either changes:
    // a fresh/rebound camera (cameraHandle) or the user toggling the top-status control
    // (torchEnabled). Fire-and-forget, like the zoom calls elsewhere on this screen - nothing
    // here needs to await the future beyond what liveTorchState reports back.
    LaunchedEffect(cameraHandle, torchEnabled) {
        cameraHandle?.cameraControl?.enableTorch(torchEnabled)
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
        if (!mode.isVideoLike) {
            isHlgActive = false
            effectiveVideoQuality = settings.videoQuality
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

    // Mirrors the bound camera's TorchState the same way the block above mirrors ZoomState,
    // so the torch chip reflects reality (e.g. the camera auto-disabling torch under thermal
    // or power constraints) rather than only the last state this screen requested.
    DisposableEffect(cameraHandle) {
        val handle = cameraHandle
        if (handle == null) {
            onDispose {}
        } else {
            val observer = Observer<Int> { liveTorchState = it }
            handle.cameraInfo.torchState.observeForever(observer)
            onDispose {
                handle.cameraInfo.torchState.removeObserver(observer)
                liveTorchState = null
            }
        }
    }

    // Capability gate for both the flash and torch controls: neither is ever shown on a
    // camera that reports no flash unit at all, per CameraInfo already exposed on the handle.
    val hasFlashUnit = cameraHandle?.cameraInfo?.hasFlashUnit() == true
    // The torch chip's displayed state - falls back to the requested torchEnabled only until
    // the observer above delivers a real reading (e.g. immediately after a fresh bind).
    val torchIsOn = liveTorchState?.let { it == TorchState.ON } ?: torchEnabled

    // Portrait capture (issue #80): a single shutter tap always runs burst -> merge ->
    // segment -> bokeh -> finish -> save; there is no separate "quick shot" affordance in
    // this mode (a single un-merged frame would defeat the point of a clean subject/
    // background split - see the empty SecondaryControlSlotWidth box for Portrait below).
    // Photo's shutter now runs the equivalent burst -> merge -> finish -> save flow too
    // (issue #101, see startPhotoCapture below), but HDR burst, night mode and
    // super-resolution are all ignored here - Portrait always merges a plain single-EV
    // BurstController.arm() burst with BurstMergePipeline regardless of what those toggles
    // are set to (SIMPLIFY decision, issue #80). BokehRenderer runs BEFORE FinishingPipeline:
    // the defocus reads the merged, still scene-referred-ish data, consistent with
    // FinishingPipeline's own stage-ordering rationale of running rendition (tone/saturation/
    // contrast) last, over effects already baked in - so the bokeh itself isn't re-tonemapped
    // by the look/preset the user picked. Mirrors startPhotoCapture's mergeAndSave structure
    // (Photo's HDR/night/SR burst dispatch) but is kept as its own function rather than
    // folded into it, since the post-merge step (segment + conditional bokeh) and the
    // distinct no-subject snackbar have no equivalent there.
    fun startPortraitCapture() {
        val controller = burstController ?: return
        val capture = imageCapture ?: return
        // Defense in depth (issue #86): ShutterButton's own `enabled` already keeps an
        // on-screen tap from reaching here while the queue is full, but the hardware
        // volume-key path (see the LaunchedEffect below) calls this function directly and
        // bypasses that Compose-level gate entirely, so the real guard has to live here too.
        // A silent no-op mirrors BurstController.arm's own "already armed" behaviour.
        if (!ProcessingQueuePolicy.canCapture(isCapturing, processingCount)) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        isCapturing = true
        currentProcessingStage = ProcessingStage.Capturing
        // Snapshot once at capture start, like mergeAndSave's guidedCaptureActive below: if
        // the user dismisses the guided banner mid-burst, this capture finishes as a normal
        // one.
        val guidedCaptureActive = guidedStep == GuidedCompareStep.AwaitingCapture
        // Selfie mirroring (issue #88): snapshot the facing that is ACTUALLY being captured,
        // not whatever lensFacing reads once merge/segment/bokeh finish - the flip-camera
        // chip stays enabled during Portrait's background processing (see its own
        // CameraSwitchLogic.isSwitchEnabled call, gated only on isRecording), so reading
        // lensFacing late could pick up a flip that happened after this burst was already
        // captured on the old lens.
        val captureLensFacing = lensFacing
        val frameCapture = BurstController.FrameCapture { onResult ->
            BurstImageCapture.acquire(capture, burstCaptureExecutor) { acquireResult ->
                onResult(acquireResult.map { jpeg -> { BurstImageCapture.decode(jpeg, maxBurstPixels) } })
            }
        }
        controller.arm(frameCapture, burstDecodeExecutor) { burst ->
            Log.d(TAG, "Portrait burst captured ${burst.frames.size} frames in ${burst.captureSpanMillis} ms")
            scope.launch(Dispatchers.Default) {
                // Frames are collected: the camera is free again, so a new capture can be
                // armed immediately while this one merges - the processing queue (issue #86)
                // takes over from here via processingCount instead of isCapturing.
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    processingCount++
                    currentProcessingStage = ProcessingStage.Merging
                }
                try {
                    // Merge/segment/bokeh all run while the chip still reads "Merging" (see
                    // ProcessingStage's own doc: it's a coarse per-job phase, not a per-op
                    // breakdown), so this one span covers all three for CaptureSpans too.
                    val mergeStart = System.currentTimeMillis()
                    val mergeResult = BurstMergePipeline.merge(burst.frames)
                    // Selfie mirroring (issue #88): mirrored ONCE here, right after merge and
                    // before segmentation, when the front lens was used - so segmentation, the
                    // bokeh mask, the finished PRT_ save, the RAW_ comparison save (below, which
                    // reuses this same `merged`), and the thumbnail all see one consistent,
                    // already-mirrored frame. Every mainstream camera app defaults selfies to
                    // match the mirrored preview PreviewView already shows the user live, rather
                    // than the sensor's raw (un-mirrored) orientation - see [mirrorHorizontal]'s
                    // KDoc for the full rationale.
                    val merged = if (captureLensFacing == LensFacing.Front) {
                        mergeResult.merged.mirrorHorizontal()
                    } else {
                        mergeResult.merged
                    }
                    // Segmentation runs on the MERGED frame (not a raw burst frame), and
                    // BLOCKS this coroutine's thread with an internal ~3s timeout - safe
                    // here since Dispatchers.Default is already off the main thread. A null
                    // mask (timeout, MLKit failure, or no confident subject) falls back to a
                    // normal finished photo instead of failing the whole capture.
                    val mask = SubjectSegmenter.segment(merged)
                    val bokehApplied = if (mask != null) {
                        BokehRenderer.render(merged, mask, BokehParams.forImageWidth(merged.width))
                    } else {
                        merged
                    }
                    val mergeMillis = System.currentTimeMillis() - mergeStart
                    withContext(Dispatchers.Main) { currentProcessingStage = ProcessingStage.Finishing }
                    // Unlike Photo's mergeAndSave, finishing is unconditional here - a POC
                    // simplification (issue #80): settings.applyFinishingToMergedPhotos only
                    // gates Photo's burst/HDR/night/SR merges, not Portrait.
                    val finishStart = System.currentTimeMillis()
                    val finished = FinishingPipeline.apply(bokehApplied, settings.finishingPreset.params)
                    val finishMillis = System.currentTimeMillis() - finishStart
                    withContext(Dispatchers.Main) { currentProcessingStage = ProcessingStage.Saving }
                    val saveStart = System.currentTimeMillis()
                    val processedUri = MergedPhotoSaver.save(
                        context,
                        finished,
                        prefix = MergedPhotoSaver.PORTRAIT_PREFIX,
                        exif = ExifMetadata(
                            captureTimestampMillis = finished.timestampMillis,
                            widthPx = finished.width,
                            heightPx = finished.height,
                        ),
                    )
                    val saveMillis = System.currentTimeMillis() - saveStart
                    val spans = burst.spans.copy(mergeMillis = mergeMillis, finishMillis = finishMillis, saveMillis = saveMillis)
                    Log.d(TAG, "Portrait capture breakdown: ${spans.formatBreakdown()}")
                    val baseMessage = if (mask != null) {
                        String.format(
                            portraitMergeSuccessMessage,
                            mergeResult.usedFrameCount,
                            finished.width,
                            finished.height,
                        )
                    } else {
                        portraitNoSubjectFoundMessage
                    }
                    val message = if (settings.verboseTimings) {
                        "$baseMessage (${spans.formatBreakdown()})"
                    } else {
                        baseMessage
                    }
                    val capturedPair = if (guidedCaptureActive) {
                        ComparePair(processedUri = processedUri, referenceUri = null)
                    } else if (settings.saveComparisonPair) {
                        val referenceUri = MergedPhotoSaver.save(
                            context,
                            merged,
                            prefix = MergedPhotoSaver.RAW_PREFIX,
                            exif = ExifMetadata(
                                captureTimestampMillis = merged.timestampMillis,
                                widthPx = merged.width,
                                heightPx = merged.height,
                            ),
                        )
                        ComparePair(processedUri = processedUri, referenceUri = referenceUri)
                    } else {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        processingCount = (processingCount - 1).coerceAtLeast(0)
                        if (processingCount == 0) currentProcessingStage = null
                        lastCapture = LastCapture(processedUri, CaptureMediaType.Photo)
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
                        processingCount = (processingCount - 1).coerceAtLeast(0)
                        if (processingCount == 0) currentProcessingStage = null
                        snackbarHostState.showSnackbar(burstMergeFailureMessage)
                    }
                }
            }
        }
    }

    // Photo capture (issue #101): the shutter is now the processed path. A single tap runs
    // the same burst -> merge (by settings toggles) -> finish -> save flow the old, now-removed
    // Burst button used - this used to be an opt-in secondary control; it is the ONLY photo
    // path unless the "Unprocessed single-shot capture" developer setting reverts the shutter
    // to the raw PhotoCapture.capture path below (see onShutterPressed). Saved with the
    // standard "IMG_" prefix (PhotoMediaStoreValuesFactory.DEFAULT_PREFIX) rather than the
    // burst-only "MRG_" of before: every photo capture goes through this path now, so there is
    // no longer a distinct "merged vs. plain" subset of captures to prefix differently - see
    // MergedPhotoSaver's own doc, which retires MRG_ entirely.
    fun startPhotoCapture() {
        val controller = burstController ?: return
        val capture = imageCapture ?: return
        // Defense in depth (issue #86, carried over from the old Burst button): the
        // ShutterButton's own `enabled` already keeps an on-screen tap from reaching here
        // while the queue is full, but the hardware volume-key path calls this function
        // directly and bypasses that Compose-level gate entirely - see
        // startPortraitCapture's matching comment.
        if (!ProcessingQueuePolicy.canCapture(isCapturing, processingCount)) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        isCapturing = true
        currentProcessingStage = ProcessingStage.Capturing
        // Selfie mirroring (issue #88): snapshot the facing being captured right now, not
        // whatever lensFacing reads once this burst's merge finishes - see
        // startPortraitCapture's matching captureLensFacing comment for why that matters.
        val captureLensFacing = lensFacing
        val frameCapture = BurstController.FrameCapture { onResult ->
            BurstImageCapture.acquire(capture, burstCaptureExecutor) { acquireResult ->
                onResult(
                    acquireResult.map { jpeg ->
                        { BurstImageCapture.decode(jpeg, maxBurstPixels) }
                    },
                )
            }
        }
        val exposure = exposureController

        // Merge (single-EV or HDR) off the main thread, then finish, persist and report;
        // shared by every dispatch branch below. spans carries the capture/decode sums
        // BurstController already measured; this closure fills in merge/finish/save around
        // its own stage transitions and logs+optionally shows the full breakdown.
        // captureLensFacing is threaded in explicitly rather than read late here - see
        // startPortraitCapture's matching comment on why: the flip-camera chip stays enabled
        // during background processing.
        val mergeAndSave: (
            spans: CaptureSpans,
            captureLensFacing: LensFacing,
            produce: suspend () -> Pair<Frame, String>,
        ) -> Unit =
            { spans, mergeLensFacing, produce ->
                // Snapshot once at merge start: if the user dismisses the guided banner
                // mid-burst, this capture finishes as a normal one.
                val guidedCaptureActive = guidedStep == GuidedCompareStep.AwaitingCapture
                scope.launch(Dispatchers.Default) {
                    // Frames are collected: the camera is free again, so a new capture can
                    // be armed immediately while this one merges - the processing queue
                    // (issue #86) takes over from here via processingCount instead of
                    // isCapturing.
                    withContext(Dispatchers.Main) {
                        isCapturing = false
                        processingCount++
                        currentProcessingStage = ProcessingStage.Merging
                    }
                    try {
                        val mergeStart = System.currentTimeMillis()
                        val (rawMerged, baseMessage) = produce()
                        // Selfie mirroring (issue #88): mirrored ONCE here, on the final
                        // merged geometry (after SR's 2x output too), before finishing - so
                        // the RAW_ comparison save below (which reuses this same `merged`)
                        // and the IMG_ save stay consistent. See Frame.mirrorHorizontal's
                        // KDoc and startPortraitCapture's matching comment for the rationale
                        // and where Portrait applies the same rule.
                        val merged = if (mergeLensFacing == LensFacing.Front) {
                            rawMerged.mirrorHorizontal()
                        } else {
                            rawMerged
                        }
                        val mergeMillis = System.currentTimeMillis() - mergeStart
                        // Finishing (tone/saturation/contrast) is optional and only ever
                        // applies to the merged burst frame. Night captures use the night
                        // finishing profile, regardless of preset; every other merge
                        // (standard or HDR) uses the user's chosen rendition preset.
                        val finishingParams = if (settings.nightModeEnabled) {
                            NightPipeline.FINISHING_PARAMS
                        } else {
                            settings.finishingPreset.params
                        }
                        withContext(Dispatchers.Main) {
                            currentProcessingStage = ProcessingStage.Finishing
                        }
                        val finishStart = System.currentTimeMillis()
                        val output = if (settings.applyFinishingToMergedPhotos) {
                            FinishingPipeline.apply(merged, finishingParams)
                        } else {
                            merged
                        }
                        val finishMillis = System.currentTimeMillis() - finishStart
                        withContext(Dispatchers.Main) {
                            currentProcessingStage = ProcessingStage.Saving
                        }
                        val saveStart = System.currentTimeMillis()
                        val processedUri = MergedPhotoSaver.save(
                            context,
                            output,
                            prefix = PhotoMediaStoreValuesFactory.DEFAULT_PREFIX,
                            exif = ExifMetadata(
                                captureTimestampMillis = output.timestampMillis,
                                widthPx = output.width,
                                heightPx = output.height,
                            ),
                        )
                        val saveMillis = System.currentTimeMillis() - saveStart
                        val finalSpans = spans.copy(
                            mergeMillis = mergeMillis,
                            finishMillis = finishMillis,
                            saveMillis = saveMillis,
                        )
                        Log.d(TAG, "Capture breakdown: ${finalSpans.formatBreakdown()}")
                        val message = if (settings.verboseTimings) {
                            "$baseMessage (${finalSpans.formatBreakdown()})"
                        } else {
                            baseMessage
                        }
                        // A guided-comparison capture always fills slot A with the
                        // processed result and leaves slot B unset until the user picks the
                        // reference photo on the Compare screen. Otherwise, persist the
                        // unprocessed merge input, as-is, for on-device A/B comparison when
                        // the user opted in via settings.
                        val capturedPair = if (guidedCaptureActive) {
                            ComparePair(processedUri = processedUri, referenceUri = null)
                        } else if (settings.saveComparisonPair) {
                            val referenceUri = MergedPhotoSaver.save(
                                context,
                                merged,
                                prefix = MergedPhotoSaver.RAW_PREFIX,
                                exif = ExifMetadata(
                                    captureTimestampMillis = merged.timestampMillis,
                                    widthPx = merged.width,
                                    heightPx = merged.height,
                                ),
                            )
                            ComparePair(processedUri = processedUri, referenceUri = referenceUri)
                        } else {
                            null
                        }
                        withContext(Dispatchers.Main) {
                            processingCount = (processingCount - 1).coerceAtLeast(0)
                            if (processingCount == 0) currentProcessingStage = null
                            lastCapture = LastCapture(processedUri, CaptureMediaType.Photo)
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
                            processingCount = (processingCount - 1).coerceAtLeast(0)
                            if (processingCount == 0) currentProcessingStage = null
                            snackbarHostState.showSnackbar(burstMergeFailureMessage)
                        }
                    }
                }
            }

        if (settings.nightModeEnabled) {
            // Night mode takes priority over HDR: a single-EV long burst merged with the
            // night profile. When both switches are on, HDR is skipped for this capture and
            // the standard merge snackbar reflects that night ran.
            controller.arm(frameCapture, burstDecodeExecutor) { burst ->
                Log.d(
                    TAG,
                    "Night burst captured ${burst.frames.size} frames " +
                        "in ${burst.captureSpanMillis} ms",
                )
                mergeAndSave(burst.spans, captureLensFacing) {
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
                decodeExecutor = burstDecodeExecutor,
            ) { burst ->
                Log.d(
                    TAG,
                    "HDR burst captured ${burst.frames.size} frames " +
                        "in ${burst.captureSpanMillis} ms",
                )
                mergeAndSave(burst.spans, captureLensFacing) {
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
            // Standard single-EV burst super-resolved onto a doubled grid. Night and HDR
            // are handled above and take priority; SR runs only on the plain still path.
            controller.arm(frameCapture, burstDecodeExecutor) { burst ->
                Log.d(
                    TAG,
                    "SR burst captured ${burst.frames.size} frames " +
                        "in ${burst.captureSpanMillis} ms",
                )
                mergeAndSave(burst.spans, captureLensFacing) {
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
            controller.arm(frameCapture, burstDecodeExecutor) { burst ->
                Log.d(TAG, "Burst captured ${burst.frames.size} frames in ${burst.captureSpanMillis} ms")
                mergeAndSave(burst.spans, captureLensFacing) {
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
    }

    // The shutter's primary action, shared by the on-screen ShutterButton and the hardware
    // volume-key path below (issue #73) - a plain local lambda (recreated every recomposition,
    // like the inline onClick it replaces) rather than a remembered reference, since it closes
    // over plain Compose state that must always read the latest value. Haptic feedback fires
    // once per event, right after the corresponding null-check so a not-yet-ready use case
    // never buzzes for a capture that didn't actually start. No shutter sound is played
    // deliberately - see the module doc note above CameraScreen.kt's imports.
    val onShutterPressed: () -> Unit = shutterAction@{
        if (mode.isVideoLike) {
            if (isRecording) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                activeRecording?.stop()
            } else {
                val capture = videoCapture ?: return@shutterAction
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
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
                            val savedUri = event.outputResults.outputUri
                            lastCapture = LastCapture(savedUri, CaptureMediaType.Video)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    String.format(
                                        videoSuccessMessage,
                                        savedUri,
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
        } else if (mode == CameraMode.Portrait) {
            startPortraitCapture()
        } else if (settings.unprocessedCapture) {
            // Developer escape hatch (issue #101): reverts the shutter to the old raw
            // CameraX path for A/B comparison and debugging against the normal processed
            // flow below. Selfie mirroring (issue #88) does NOT apply here: CameraX's
            // ImageCapture.takePicture writes the JPEG straight to disk itself
            // (PhotoCapture never sees decoded pixels to mirror), unlike every other photo
            // path (burst/HDR/night/SR merges and Portrait), which all pass their merged
            // Frame through Frame.mirrorHorizontal before saving. CameraX 1.5's
            // ImageCapture.Builder.setMirrorMode exists, but its effect on the JPEG output
            // was left undocumented in this project's own findings (issue #71) - shipping
            // an untested, per-device-unknown mirror on the one path we don't control the
            // pixels for would trade a known, honest inconsistency for an unverified one.
            // Documented, not hidden.
            val capture = imageCapture ?: return@shutterAction
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            PhotoCapture.capture(
                context = context,
                imageCapture = capture,
                onSuccess = { uri ->
                    lastCapture = LastCapture(uri, CaptureMediaType.Photo)
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
        } else {
            // Photo mode's normal path (issue #101): the shutter IS the processed path -
            // burst -> merge (by settings toggles) -> finish -> save, same as Portrait's
            // shutter and the old, now-removed Burst button. See startPhotoCapture above.
            startPhotoCapture()
        }
    }

    // Bridges MainActivity's hardware volume-key dispatch into this screen's own shutter
    // action - Activity.onKeyDown can't reach into composable state directly, so
    // MainActivity increments volumeShutterTrigger instead (see VolumeShutterPolicy for the
    // gating decision made there).
    //
    // Edge-trigger (issue #143): volumeShutterTrigger is an Activity-scoped level/counter, but
    // this consumer's LaunchedEffect is re-run every time CameraScreen re-enters composition -
    // which a Settings/Compare round trip forces by disposing and rebuilding this whole
    // subtree. A bare `if (trigger > 0)` therefore re-fired the shutter on every return after
    // any real press this session (a phantom photo, or a stray record start/stop). We instead
    // seed a per-consumer baseline from the current counter and fire only when it has since
    // advanced past that baseline (VolumeShutterPolicy.isFreshTrigger).
    //
    // Plain `remember`, deliberately NOT `rememberSaveable`: the baseline must mirror the
    // trigger's own lifetime, and the trigger is a plain Activity field (MainActivity) with no
    // configChanges override in the manifest, so it resets to 0 whenever the Activity is
    // recreated (rotation / process death). Re-seeding the baseline from the trigger on each
    // fresh composition keeps the two in lock-step - baseline == trigger right after any
    // recreation, so no phantom fire. A rememberSaveable baseline would instead survive that
    // recreation and, paired with the reset (0) trigger, would fire a phantom capture on the
    // first rotation after a real press. It restores across nav too, because the trigger is
    // preserved across nav (Activity not recreated) and the re-seed reads that preserved value.
    var lastHandledTrigger by remember { mutableIntStateOf(volumeShutterTrigger) }
    LaunchedEffect(volumeShutterTrigger) {
        if (VolumeShutterPolicy.isFreshTrigger(volumeShutterTrigger, lastHandledTrigger)) {
            lastHandledTrigger = volumeShutterTrigger
            onShutterPressed()
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
            lensFacing = lensFacing,
            desiredZoomRatio = zoomRatio,
            flashMode = flashMode,
            hdrVideoEnabled = settings.hdrVideoEnabled,
            videoQuality = settings.videoQuality,
            modifier = Modifier.fillMaxSize(),
            onImageCaptureReady = { imageCapture = it },
            onVideoCaptureReady = { videoCapture = it },
            onBurstControllerReady = { burstController = it },
            onExposureControllerReady = { exposureController = it },
            onCinematicConfigReady = { cinematicConfig = it },
            onVideoConfigResolved = { isHlg, effectiveQuality ->
                isHlgActive = isHlg
                effectiveVideoQuality = effectiveQuality
            },
            onCameraAvailability = { back, front ->
                hasBackCamera = back
                hasFrontCamera = front
            },
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

        // Top bar (issue #82): one row spanning the width - passive status chips on the
        // LEFT, action icon buttons on the RIGHT - so read-only status and interactive
        // actions never mix into one ambiguous strip of differently-shaped chips the way
        // they did before. The guided-capture banner, when present, sits centred below it.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = ChromeTokens.EdgeInset, end = ChromeTokens.EdgeInset, top = ChromeTokens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // LEFT: passive status chips only, all in one StatusChip style. weight(fill
                // = false) lets a long cinematic-config chip take the space it needs without
                // ever pushing into the action buttons.
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing),
                ) {
                    if (isRecording) {
                        StatusChip(
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
                                style = chromeTextStyle(),
                            )
                        }
                    }

                    // HDR status is only ever worth showing once the user has opted into it
                    // in settings - with it off every recording is trivially SDR, so a
                    // permanent "SDR" chip/suffix would just be noise on every session.
                    val videoRangeLabel = if (settings.hdrVideoEnabled) {
                        if (isHlgActive) videoHdrStatusHlg10Label else videoHdrStatusSdrLabel
                    } else {
                        null
                    }
                    // Quality mismatch (issue #72): only worth a chip once the Recorder
                    // actually landed somewhere other than the user's own choice - see
                    // VideoQualityLogic.showQualityChip. Reuses the same "%1$s - %2$s" join
                    // as the HDR suffix (VideoOverlayLabel) so a HDR+quality mismatch reads
                    // as one chip, e.g. "HLG10 - 1080p", instead of two.
                    val qualityMismatchLabel = if (VideoQualityLogic.showQualityChip(settings.videoQuality, effectiveVideoQuality)) {
                        effectiveVideoQuality.displayLabel
                    } else {
                        null
                    }
                    val videoOverlayLabel = VideoOverlayLabel.combine(
                        rangeLabel = videoRangeLabel,
                        qualityLabel = qualityMismatchLabel,
                        joinTemplate = cinematicRangeSuffixTemplate,
                    )
                    val qualityMismatchContentDescription = qualityMismatchLabel?.let {
                        String.format(
                            videoQualityMismatchContentDescriptionTemplate,
                            effectiveVideoQuality.displayLabel,
                            settings.videoQuality.displayLabel,
                        )
                    }

                    if (mode == CameraMode.Video && videoOverlayLabel != null) {
                        StatusChip(
                            text = videoOverlayLabel,
                            contentDescription = qualityMismatchContentDescription,
                        )
                    }

                    if (mode == CameraMode.Cinematic) {
                        cinematicConfig?.let { config ->
                            StatusChip(
                                text = CinematicOverlayText.format(
                                    config = config,
                                    fps24Label = cinematicFps24Label,
                                    fpsDefaultLabel = cinematicFpsDefaultLabel,
                                    stabilizedTemplate = cinematicStabilizedTemplate,
                                    unstabilizedTemplate = cinematicUnstabilizedTemplate,
                                    rangeLabel = videoOverlayLabel,
                                    rangeSuffixTemplate = cinematicRangeSuffixTemplate,
                                ),
                                contentDescription = qualityMismatchContentDescription,
                            )
                        }
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
                }

                // RIGHT: action icon buttons only - flash/torch, compare, settings - evenly
                // spaced with identical 48dp targets and shared circular-scrim styling.
                Row(horizontalArrangement = Arrangement.spacedBy(ChromeTokens.ActionSpacing)) {
                    // Portrait binds an ImageCapture just like Photo (see CameraPreview) and
                    // applies flashMode the same dynamic way, so flash shows for both. The
                    // three distinct bolt glyphs carry the off/auto/on state (non-colour).
                    if (hasFlashUnit && (mode == CameraMode.Photo || mode == CameraMode.Portrait)) {
                        ActionIconButton(
                            icon = when (flashMode) {
                                FlashMode.Off -> CameraGlyphs.FlashOff
                                FlashMode.Auto -> CameraGlyphs.FlashAuto
                                FlashMode.On -> CameraGlyphs.FlashOn
                            },
                            contentDescription = when (flashMode) {
                                FlashMode.Off -> flashContentDescriptionOff
                                FlashMode.Auto -> flashContentDescriptionAuto
                                FlashMode.On -> flashContentDescriptionOn
                            },
                            onClick = { flashMode = flashMode.next() },
                        )
                    }

                    // Torch is a two-state toggle: the inverted (white fill / dark icon)
                    // active state is its non-colour cue, mirrored in the content description.
                    if (hasFlashUnit && mode.isVideoLike) {
                        ActionIconButton(
                            icon = CameraGlyphs.Torch,
                            contentDescription = if (torchIsOn) {
                                torchContentDescriptionOn
                            } else {
                                torchContentDescriptionOff
                            },
                            active = torchIsOn,
                            onClick = { torchEnabled = TorchLogic.toggle(torchIsOn) },
                        )
                    }

                    // Only offered once this session has produced a processed/reference pair
                    // (i.e. "Save comparison pair" was on for at least one burst).
                    if (comparePair != null) {
                        ActionIconButton(
                            icon = CameraGlyphs.Compare,
                            contentDescription = compareActionLabel,
                            enabled = !isRecording,
                            onClick = onOpenCompare,
                        )
                    }

                    ActionIconButton(
                        icon = CameraGlyphs.Settings,
                        contentDescription = openSettingsContentDescription,
                        enabled = !isRecording,
                        onClick = onOpenSettings,
                    )
                }
            }

            if (guidedStep == GuidedCompareStep.AwaitingCapture) {
                GuidedCaptureBanner(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    message = guidedCaptureBannerMessage,
                    detail = guidedCaptureBannerDetail,
                    cancelContentDescription = guidedCancelContentDescription,
                    onCancel = onGuidedCancel,
                )
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

        // Last-capture thumbnail (issue #74) + processing indicator (issue #86): bottom-start,
        // clear of the shutter cluster (bottom-center) and its flanking burst/look controls,
        // and clear of the top-start Compare button/top-end Settings gear. The stage StatusChip
        // sits right above the slot it describes, quiet by design: no full-screen overlay, no
        // blocking dialog, just the same chrome language as every other status chip. The slot
        // is present whenever there is a previous capture to show OR a job is currently
        // processing - the empty scrim tile below covers the case where the very first capture
        // of a session is still merging and there is no thumbnail yet to overlay a spinner on.
        // Absent entirely otherwise - no empty placeholder - per lastCapture's own nullability.
        if (lastCapture != null || processingCount > 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing),
            ) {
                if (processingCount > 0) {
                    StatusChip(
                        text = ProcessingQueuePolicy.chipText(
                            // A job is in flight (processingCount > 0), so it has already
                            // moved past Capturing - see ProcessingStage's own doc for why
                            // that value never actually reaches this chip.
                            stage = currentProcessingStage ?: ProcessingStage.Merging,
                            processingCount = processingCount,
                            capturingLabel = processingStageCapturingLabel,
                            mergingLabel = processingStageMergingLabel,
                            finishingLabel = processingStageFinishingLabel,
                            savingLabel = processingStageSavingLabel,
                            countSuffixTemplate = processingStageCountSuffixTemplate,
                        ),
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    val capture = lastCapture
                    if (capture != null) {
                        LastCaptureThumbnail(
                            capture = capture,
                            contentDescription = lastCaptureContentDescription,
                            onTap = {
                                val queriedMimeType = context.contentResolver.getType(capture.uri)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(
                                        capture.uri,
                                        LastCaptureMimeType.resolve(queriedMimeType, capture.mediaType),
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(lastCaptureOpenFailureMessage)
                                    }
                                }
                            },
                        )
                    } else {
                        // No capture yet this session, but one is already processing (the
                        // very first capture): a bare scrim tile the same shape/size as the
                        // real thumbnail, just so the spinner below has a slot to sit in.
                        Box(
                            modifier = Modifier
                                .size(LastCaptureThumbnailSize)
                                .clip(RoundedCornerShape(LastCaptureThumbnailCornerRadius))
                                .background(OverlayScrimColor),
                        )
                    }
                    if (processingCount > 0) {
                        // Small and indeterminate by design (issue #86): a quiet corner
                        // indicator, not a full-screen overlay or blocking dialog. The stage
                        // StatusChip above already carries the accessible label, so this stays
                        // purely decorative rather than duplicating it.
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = ChromeTokens.OnChrome,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        // Bottom cluster (issue #82): the mode selector over the shutter row, with no
        // wrapping scrim box. Each control now carries its own minimal backing - the
        // segmented selector its own surface, the secondary controls the shared circular
        // scrim, the shutter its ring - so the chrome reads as one system rather than a
        // box inside a box. Flip camera (issue #71) moves into the right secondary slot as
        // an icon button beside the shutter, no longer a text chip on its own row above.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ChromeTokens.ClusterSpacing),
            ) {
                ModeSelector(
                    selected = mode,
                    enabled = !isRecording,
                    photoLabel = photoModeLabel,
                    portraitLabel = portraitModeLabel,
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
                        // Left secondary slot (issue #101): Photo no longer has an
                        // independent Burst button here - the shutter itself now runs that
                        // flow (see startPhotoCapture), so this slot renders nothing for
                        // Photo, exactly like it already did for Portrait (whose shutter was
                        // always the sole capture trigger). Only Cinematic still has a
                        // control here (the Look toggle, unrelated to burst/merge and not a
                        // sensible fit for this slot in any other mode).
                        if (mode == CameraMode.Cinematic) {
                            // Look toggle (issue #82): the two-option segmented selector is
                            // now a single droplet icon button that cycles the look, with the
                            // active look name shown small beneath - the one secondary control
                            // that keeps a visible label, since which look is applied
                            // materially changes the recording. Same videoLook state, same
                            // reachability (one tap to switch between the two looks).
                            LookToggleButton(
                                selected = videoLook,
                                enabled = !isRecording,
                                neutralLabel = neutralLookLabel,
                                cinematicLabel = cinematicLookShortLabel,
                                contentDescription = String.format(
                                    lookToggleContentDescriptionTemplate,
                                    if (videoLook == VideoLook.Neutral) {
                                        neutralLookLabel
                                    } else {
                                        cinematicLookShortLabel
                                    },
                                ),
                                onToggle = {
                                    videoLook = if (videoLook == VideoLook.Neutral) {
                                        VideoLook.Cinematic
                                    } else {
                                        VideoLook.Neutral
                                    }
                                },
                            )
                        }
                    }

                    ShutterButton(
                        mode = mode,
                        isRecording = isRecording,
                        // Portrait's and (issue #101) Photo's shutter are both the burst
                        // trigger now (see startPortraitCapture/startPhotoCapture), so both
                        // respect the same processing-queue gate (issue #86). Video/Cinematic
                        // have their own start/stop mechanic, so neither is part of this
                        // queue - always enabled here. The "Unprocessed single-shot capture"
                        // developer setting reverts Photo to the old raw, un-merged
                        // PhotoCapture.capture path, which never touches isCapturing/
                        // processingCount at all - so it stays always enabled here too,
                        // exactly like it did before this issue.
                        enabled = when {
                            mode.isVideoLike -> true
                            mode == CameraMode.Photo && settings.unprocessedCapture -> true
                            else -> ProcessingQueuePolicy.canCapture(isCapturing, processingCount)
                        },
                        contentDescription = when {
                            mode.isVideoLike && isRecording -> stopContentDescription
                            mode.isVideoLike -> recordContentDescription
                            else -> shutterContentDescription
                        },
                        onClick = onShutterPressed,
                    )

                    // Right secondary slot: flip camera (issue #71), now an icon button
                    // beside the shutter instead of a text chip on its own row. Hidden on a
                    // single-camera device, disabled while recording (out of scope), per
                    // CameraSwitchLogic - unchanged behaviour, only the affordance changed.
                    Box(
                        modifier = Modifier.width(SecondaryControlSlotWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (CameraSwitchLogic.shouldShowSwitchChip(hasBackCamera, hasFrontCamera)) {
                            ActionIconButton(
                                icon = CameraGlyphs.FlipCamera,
                                contentDescription = switchCameraContentDescription,
                                enabled = CameraSwitchLogic.isSwitchEnabled(isRecording),
                                onClick = {
                                    val reset = CameraSwitchLogic.resetsForSwitch()
                                    zoomRatio = reset.zoomRatio
                                    torchEnabled = reset.torchEnabled
                                    lensFacing = lensFacing.switched()
                                },
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

/**
 * Larger scrim-backed message surface for multi-line overlay content - the guided-capture
 * banner and the bind-error card - sharing the chrome's single scrim and corner radius
 * ([ChromeTokens]) with the compact [StatusChip], just with roomier padding.
 */
@Composable
private fun OverlayChip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(ChromeTokens.Scrim, shape = ChromeTokens.ChipShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/**
 * Current-zoom indicator: visually a passive [StatusChip] so it sits at the same height as
 * every other left-column status chip, but wrapped in a transparent 48dp box that carries
 * the zoom-reset tap target (WCAG AA) without making the chip itself taller than its
 * neighbours. The visible label is the actual clamped ratio, never an aspirational "1.0x",
 * so a device whose minimum sits above 1x is never misrepresented. Callers gate visibility
 * with [ZoomLogic.shouldShowChip] - this composable has no opinion on when it should show.
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
            .sizeIn(minWidth = ChromeTokens.TouchTarget, minHeight = ChromeTokens.TouchTarget)
            .clickable(onClick = onReset)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = resetContentDescription
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        StatusChip {
            Text(text = ZoomLogic.formatLabel(ratio), style = chromeTextStyle())
        }
    }
}

/**
 * Last-capture thumbnail (issue #74): a 56dp rounded-corner tile showing the most recent
 * capture this session, tappable to open it in the system viewer. The tile itself is
 * always the [OverlayScrimColor] dark scrim so there is no flash of white/blank content
 * before the async decode in [rememberLastCaptureThumbnailBitmap] resolves; a video
 * capture always gets the small play-triangle overlay, whether or not a real decoded
 * frame loaded underneath it (see [loadVideoThumbnail] for the pre-API-29 fallback that
 * leaves the bitmap null).
 */
@Composable
private fun LastCaptureThumbnail(
    capture: LastCapture,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberLastCaptureThumbnailBitmap(capture)
    Box(
        modifier = modifier
            .size(LastCaptureThumbnailSize)
            .clip(RoundedCornerShape(LastCaptureThumbnailCornerRadius))
            .background(OverlayScrimColor)
            .clickable(onClick = onTap)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (capture.mediaType == CaptureMediaType.Video) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = Color.White)
            }
        }
    }
}

/**
 * Decodes [capture]'s thumbnail off the main thread, cached per URI (the `remember`/
 * `LaunchedEffect` both key on it, matching the pattern CompareScreen uses for its own
 * reference/processed slots) so switching back to an already-seen capture never
 * re-decodes. Photo URIs reuse [ReferenceImageLoader] - the same downsample-then-decode
 * path CompareScreen's slots use - just with a much smaller target size. Video URIs have
 * no equivalent decoder in this project, so they go through [loadVideoThumbnail] instead;
 * see its doc for the pre-API-29 fallback.
 */
@Composable
private fun rememberLastCaptureThumbnailBitmap(capture: LastCapture): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(capture.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(capture.uri) {
        bitmap = withContext(Dispatchers.IO) {
            when (capture.mediaType) {
                CaptureMediaType.Photo -> ReferenceImageLoader.loadDownsampled(
                    context = context,
                    uri = capture.uri,
                    maxDimensionPx = LAST_CAPTURE_THUMBNAIL_DECODE_PX,
                )
                CaptureMediaType.Video -> loadVideoThumbnail(context, capture.uri)
            }
        }
    }
    return bitmap
}

/**
 * `ContentResolver.loadThumbnail` (the only generic, codec-agnostic MediaStore video
 * thumbnail API) only exists from API 29 onward - minSdk for this project is 26, so
 * below that this always returns null and [LastCaptureThumbnail] falls back to its plain
 * dark tile plus the play-triangle overlay instead of a decoded frame. Any decode failure
 * (revoked grant, missing row, codec error) is swallowed the same way
 * [ReferenceImageLoader] swallows its own failures - a missing thumbnail is a normal,
 * recoverable UI state, not an exceptional one.
 */
private fun loadVideoThumbnail(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return null
    }
    return try {
        context.contentResolver.loadThumbnail(
            uri,
            android.util.Size(LAST_CAPTURE_THUMBNAIL_DECODE_PX, LAST_CAPTURE_THUMBNAIL_DECODE_PX),
            null,
        )
    } catch (e: Exception) {
        null
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
    portraitLabel: String,
    videoLabel: String,
    cinematicLabel: String,
    onSelected: (CameraMode) -> Unit,
) {
    // Photo/Portrait grouped first (both photo-like, sharing the Preview + ImageCapture
    // bind), then the two video-like modes - issue #80 adds Portrait as the 4th entry.
    val options = listOf(
        CameraMode.Photo to photoLabel,
        CameraMode.Portrait to portraitLabel,
        CameraMode.Video to videoLabel,
        CameraMode.Cinematic to cinematicLabel,
    )
    // Widened from the original 3-entry 320.dp and each label capped at one line so 4 short
    // labels - including the 9-character "Cinematic" and 8-character "Portrait" - never wrap
    // on a narrow screen.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 360.dp)) {
        options.forEachIndexed { index, (candidateMode, label) ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = selected == candidateMode,
                enabled = enabled,
                onClick = { onSelected(candidateMode) },
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
 * Cinematic look control (issue #82): a single droplet [ActionIconButton] that cycles the
 * two looks, with the active look name shown small beneath. It replaces the old two-segment
 * selector but drives the same `videoLook` state - with only two looks, one tap still
 * switches between them. The applied (Cinematic) look inverts the button as a non-colour
 * active cue, and the visible label plus content description keep the state legible without
 * relying on colour.
 */
@Composable
private fun LookToggleButton(
    selected: VideoLook,
    enabled: Boolean,
    neutralLabel: String,
    cinematicLabel: String,
    contentDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionIconButton(
            icon = CameraGlyphs.Look,
            contentDescription = contentDescription,
            enabled = enabled,
            active = selected == VideoLook.Cinematic,
            onClick = onToggle,
        )
        Text(
            text = if (selected == VideoLook.Neutral) neutralLabel else cinematicLabel,
            style = chromeTextStyle(),
            maxLines = 1,
        )
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
    // Portrait mode routes the shutter through the processing queue (issue #86) - disabled
    // while the queue can't accept a new capture, dimmed the same way ActionIconButton dims
    // a disabled control elsewhere on this chrome.
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else ChromeTokens.DisabledAlpha)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
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
 * Transient tap-to-focus feedback near the tap point (redesigned, issue #82): four thin
 * L-shaped corner brackets - a quieter photographic convention than the old stroked square
 * with a check/cross glyph. State is read through geometry and brightness, never colour:
 * while focusing the brackets sit at full size in near-white; on lock they contract slightly
 * and brighten to solid white; on failure they push apart and dim. Colour is a single flat
 * white throughout, so there are no competing accents. Not a control (see [FocusReticleSize]),
 * so it stays well under the 48dp minimum touch target used elsewhere on this screen.
 *
 * The contract/break motion respects the system reduced-motion setting: when animations are
 * disabled ([Settings.Global.ANIMATOR_DURATION_SCALE] is 0) the brackets snap to their
 * resolved geometry instead of tweening.
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
    val targetScale = when (state) {
        is FocusReticleState.Focusing -> FOCUS_RETICLE_SCALE_FOCUSING
        is FocusReticleState.Focused -> FOCUS_RETICLE_SCALE_FOCUSED
        is FocusReticleState.Failed -> FOCUS_RETICLE_SCALE_FAILED
        FocusReticleState.Idle -> return
    }
    val stateAlpha = when (state) {
        is FocusReticleState.Focusing -> FOCUS_RETICLE_ALPHA_FOCUSING
        is FocusReticleState.Focused -> FOCUS_RETICLE_ALPHA_FOCUSED
        is FocusReticleState.Failed -> FOCUS_RETICLE_ALPHA_FAILED
        FocusReticleState.Idle -> return
    }

    val context = LocalContext.current
    val motionEnabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(
            durationMillis = if (motionEnabled) FOCUS_RETICLE_SCALE_MILLIS else 0,
            easing = LinearEasing,
        ),
        label = "focus_reticle_scale",
    )

    Canvas(
        modifier = modifier
            .offset {
                val halfSizePx = FocusReticleSize.toPx() / 2f
                IntOffset((point.x - halfSizePx).roundToInt(), (point.y - halfSizePx).roundToInt())
            }
            .size(FocusReticleSize)
            .alpha(alpha),
    ) {
        val strokeWidthPx = FocusReticleStrokeWidth.toPx()
        val armPx = FocusReticleCornerArm.toPx()
        val color = Color.White.copy(alpha = stateAlpha)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = (size.minDimension / 2f - strokeWidthPx) * animatedScale
        val left = cx - half
        val right = cx + half
        val top = cy - half
        val bottom = cy + half

        // Each corner is an L: one arm toward centre along x, one along y. dx/dy point the
        // arms inward from that corner.
        val corners = listOf(
            Triple(Offset(left, top), 1f, 1f),
            Triple(Offset(right, top), -1f, 1f),
            Triple(Offset(left, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        )
        for ((corner, dx, dy) in corners) {
            drawLine(
                color = color,
                start = corner,
                end = Offset(corner.x + dx * armPx, corner.y),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = corner,
                end = Offset(corner.x, corner.y + dy * armPx),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
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
