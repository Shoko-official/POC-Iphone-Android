package com.poc.camera.compare

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poc.camera.R
import com.poc.camera.imaging.BitmapFrameConverter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Two peer inspection modes for the same A/B pair; see [SingleChoiceSegmentedButtonRow] below. */
private enum class CompareViewMode { SideBySide, Swipe }

/**
 * Bitmaps are decoded at roughly this size (see [ImageDownsampler]) - enough detail
 * for on-device pixel-level inspection without risking OOM on a 12MP+ import.
 */
private const val MaxDecodeDimensionPx = 2048

private sealed interface ImageSlotState {
    data object Empty : ImageSlotState
    data object Loading : ImageSlotState
    data class Loaded(val bitmap: Bitmap) : ImageSlotState
    data object Failed : ImageSlotState
}

/**
 * A/B comparison viewer for a processed capture ("A") against either its own
 * unprocessed merge input or an imported external reference photo ("B", e.g. an
 * iPhone shot of the same scene). Full-screen review surface (not a live
 * viewfinder), so it follows the [com.poc.camera.settings.SettingsScreen]
 * Scaffold/TopAppBar convention rather than CameraScreen's overlay chrome, but
 * keeps the image area black and dominant with minimal, dismissible controls.
 *
 * Side-by-side pan/zoom sync is intentionally out of scope for this pass - both
 * panes use static fit-to-screen, which is sufficient to spot processing
 * differences at a glance; documented here rather than silently degraded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    pair: ComparePair?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var mode by rememberSaveable { mutableStateOf(CompareViewMode.SideBySide) }
    var referenceUri by remember(pair) { mutableStateOf(pair?.referenceUri) }
    var processedState by remember(pair) {
        mutableStateOf<ImageSlotState>(if (pair?.processedUri != null) ImageSlotState.Loading else ImageSlotState.Empty)
    }
    var referenceState by remember(referenceUri) {
        mutableStateOf<ImageSlotState>(if (referenceUri != null) ImageSlotState.Loading else ImageSlotState.Empty)
    }
    var metricsVisible by rememberSaveable { mutableStateOf(false) }
    var metricsResult by remember { mutableStateOf<CompareMetricsResult?>(null) }
    var metricsComparable by remember { mutableStateOf(true) }
    var dividerFraction by rememberSaveable { mutableFloatStateOf(0.5f) }

    val pickReferenceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) referenceUri = uri }

    LaunchedEffect(pair?.processedUri) {
        val uri = pair?.processedUri
        if (uri == null) {
            processedState = ImageSlotState.Empty
        } else {
            processedState = ImageSlotState.Loading
            val bitmap = withContext(Dispatchers.IO) {
                ReferenceImageLoader.loadDownsampled(context, uri, MaxDecodeDimensionPx)
            }
            processedState = if (bitmap != null) ImageSlotState.Loaded(bitmap) else ImageSlotState.Failed
        }
    }

    LaunchedEffect(referenceUri) {
        val uri = referenceUri
        if (uri == null) {
            referenceState = ImageSlotState.Empty
        } else {
            referenceState = ImageSlotState.Loading
            val bitmap = withContext(Dispatchers.IO) {
                ReferenceImageLoader.loadDownsampled(context, uri, MaxDecodeDimensionPx)
            }
            referenceState = if (bitmap != null) ImageSlotState.Loaded(bitmap) else ImageSlotState.Failed
        }
    }

    val processedBitmap = (processedState as? ImageSlotState.Loaded)?.bitmap
    val referenceBitmap = (referenceState as? ImageSlotState.Loaded)?.bitmap

    LaunchedEffect(processedBitmap, referenceBitmap, metricsVisible) {
        if (!metricsVisible || processedBitmap == null || referenceBitmap == null) {
            metricsResult = null
            metricsComparable = true
        } else {
            val result = withContext(Dispatchers.Default) {
                CompareMetrics.compute(
                    processed = BitmapFrameConverter.toFrame(processedBitmap),
                    reference = BitmapFrameConverter.toFrame(referenceBitmap),
                )
            }
            metricsResult = result
            metricsComparable = result != null
        }
    }

    val sideBySideLabel = stringResource(R.string.compare_mode_side_by_side)
    val swipeLabel = stringResource(R.string.compare_mode_swipe)
    val processedLabel = stringResource(R.string.compare_label_processed)
    val referenceLabel = stringResource(R.string.compare_label_reference)
    val emptyProcessedMessage = stringResource(R.string.compare_empty_processed)
    val emptyReferenceMessage = stringResource(R.string.compare_empty_reference)
    val loadFailureMessage = stringResource(R.string.compare_load_failure)
    val loadReferenceLabel = stringResource(R.string.compare_load_reference_label)
    val showMetricsLabel = stringResource(R.string.compare_metrics_show)
    val hideMetricsLabel = stringResource(R.string.compare_metrics_hide)
    val psnrLabel = stringResource(R.string.compare_metrics_psnr)
    val ssimLabel = stringResource(R.string.compare_metrics_ssim)
    val maeLabel = stringResource(R.string.compare_metrics_mae)
    val notComparableMessage = stringResource(R.string.compare_metrics_not_comparable)
    val computingMessage = stringResource(R.string.compare_metrics_computing)
    val dividerContentDescription = stringResource(R.string.compare_swipe_divider_content_description)

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.compare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compare_back_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize(),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                CompareViewMode.entries.forEachIndexed { index, candidate ->
                    SegmentedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CompareViewMode.entries.size),
                        selected = mode == candidate,
                        onClick = { mode = candidate },
                        icon = {},
                        label = {
                            Text(
                                text = when (candidate) {
                                    CompareViewMode.SideBySide -> sideBySideLabel
                                    CompareViewMode.Swipe -> swipeLabel
                                },
                            )
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (mode) {
                    CompareViewMode.SideBySide -> SideBySideComparison(
                        processedState = processedState,
                        referenceState = referenceState,
                        processedLabel = processedLabel,
                        referenceLabel = referenceLabel,
                        emptyProcessedMessage = emptyProcessedMessage,
                        emptyReferenceMessage = emptyReferenceMessage,
                        loadFailureMessage = loadFailureMessage,
                    )
                    CompareViewMode.Swipe -> SwipeComparison(
                        processedState = processedState,
                        referenceState = referenceState,
                        processedLabel = processedLabel,
                        referenceLabel = referenceLabel,
                        emptyProcessedMessage = emptyProcessedMessage,
                        emptyReferenceMessage = emptyReferenceMessage,
                        loadFailureMessage = loadFailureMessage,
                        dividerFraction = dividerFraction,
                        onDividerFractionChanged = { dividerFraction = it },
                        dividerContentDescription = dividerContentDescription,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            pickReferenceLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Text(text = loadReferenceLabel)
                    }
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { metricsVisible = !metricsVisible },
                    ) {
                        Text(text = if (metricsVisible) hideMetricsLabel else showMetricsLabel)
                    }
                }

                AnimatedVisibility(visible = metricsVisible) {
                    MetricsPanel(
                        processedReady = processedBitmap != null,
                        referenceReady = referenceBitmap != null,
                        comparable = metricsComparable,
                        result = metricsResult,
                        psnrLabel = psnrLabel,
                        ssimLabel = ssimLabel,
                        maeLabel = maeLabel,
                        notComparableMessage = notComparableMessage,
                        computingMessage = computingMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun SideBySideComparison(
    processedState: ImageSlotState,
    referenceState: ImageSlotState,
    processedLabel: String,
    referenceLabel: String,
    emptyProcessedMessage: String,
    emptyReferenceMessage: String,
    loadFailureMessage: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            ImageSlot(
                state = processedState,
                label = processedLabel,
                emptyMessage = emptyProcessedMessage,
                loadFailureMessage = loadFailureMessage,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.24f)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            ImageSlot(
                state = referenceState,
                label = referenceLabel,
                emptyMessage = emptyReferenceMessage,
                loadFailureMessage = loadFailureMessage,
            )
        }
    }
}

@Composable
private fun ImageSlot(
    state: ImageSlotState,
    label: String,
    emptyMessage: String,
    loadFailureMessage: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ImageSlotState.Loaded -> {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                LabelChip(text = label, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            ImageSlotState.Loading -> CircularProgressIndicator(color = Color.White)
            ImageSlotState.Empty -> SlotMessage(emptyMessage)
            ImageSlotState.Failed -> SlotMessage(loadFailureMessage)
        }
    }
}

@Composable
private fun SwipeComparison(
    processedState: ImageSlotState,
    referenceState: ImageSlotState,
    processedLabel: String,
    referenceLabel: String,
    emptyProcessedMessage: String,
    emptyReferenceMessage: String,
    loadFailureMessage: String,
    dividerFraction: Float,
    onDividerFractionChanged: (Float) -> Unit,
    dividerContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val processedBitmap = (processedState as? ImageSlotState.Loaded)?.bitmap
    val referenceBitmap = (referenceState as? ImageSlotState.Loaded)?.bitmap

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / size.width.toFloat()
                        onDividerFractionChanged((dividerFraction + delta).coerceIn(0f, 1f))
                    }
                },
        ) {
            // Bottom layer: reference, shown across the full area.
            when {
                referenceBitmap != null -> {
                    Image(
                        bitmap = referenceBitmap.asImageBitmap(),
                        contentDescription = referenceLabel,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    LabelChip(referenceLabel, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
                referenceState == ImageSlotState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
                referenceState == ImageSlotState.Failed ->
                    SlotMessage(loadFailureMessage, Modifier.align(Alignment.Center))
                else -> SlotMessage(emptyReferenceMessage, Modifier.align(Alignment.Center))
            }

            // Top layer: processed, clipped to the divider so only the left portion shows.
            when {
                processedBitmap != null -> {
                    Image(
                        bitmap = processedBitmap.asImageBitmap(),
                        contentDescription = processedLabel,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipRect(right = size.width * dividerFraction) {
                                    this@drawWithContent.drawContent()
                                }
                            },
                    )
                    LabelChip(processedLabel, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                processedState == ImageSlotState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
                processedState == ImageSlotState.Failed ->
                    SlotMessage(loadFailureMessage, Modifier.align(Alignment.Center))
                else -> SlotMessage(emptyProcessedMessage, Modifier.align(Alignment.Center))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * dividerFraction - 1.dp)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Color.White)
                    .semantics { contentDescription = dividerContentDescription },
            )
        }

        // Non-drag alternative to the divider drag gesture: fully keyboard/TalkBack operable.
        Slider(
            value = dividerFraction,
            onValueChange = onDividerFractionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .semantics { contentDescription = dividerContentDescription },
        )
    }
}

@Composable
private fun LabelChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SlotMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}

@Composable
private fun MetricsPanel(
    processedReady: Boolean,
    referenceReady: Boolean,
    comparable: Boolean,
    result: CompareMetricsResult?,
    psnrLabel: String,
    ssimLabel: String,
    maeLabel: String,
    notComparableMessage: String,
    computingMessage: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            !processedReady || !referenceReady ->
                Text(text = computingMessage, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            !comparable ->
                Text(text = notComparableMessage, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            result == null ->
                Text(text = computingMessage, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            else -> {
                MetricRow(psnrLabel, formatPsnr(result.psnrDb))
                MetricRow(ssimLabel, String.format(Locale.ROOT, "%.4f", result.ssim))
                MetricRow(maeLabel, String.format(Locale.ROOT, "%.2f", result.mae))
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatPsnr(value: Double): String =
    if (value.isInfinite()) "∞ dB" else String.format(Locale.ROOT, "%.2f dB", value)
