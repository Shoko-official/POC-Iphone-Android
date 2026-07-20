package com.poc.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.poc.camera.camera.CameraScreen
import com.poc.camera.compare.ComparePair
import com.poc.camera.compare.CompareScreen
import com.poc.camera.compare.GuidedCompareEvent
import com.poc.camera.compare.GuidedCompareFlow
import com.poc.camera.compare.GuidedCompareStep
import com.poc.camera.settings.CameraSettings
import com.poc.camera.settings.SettingsScreen
import com.poc.camera.settings.SharedPreferencesCameraSettings
import com.poc.camera.ui.theme.PocCameraTheme

private enum class AppDestination {
    Camera,
    Settings,
    Compare,
}

/** Sentinel standing in for a null [ComparePair.referenceUri] in the flat string list
 * [ComparePairSaver] round-trips through `rememberSaveable`. */
private const val NoReferenceUriMarker = ""

/** Round-trips [ComparePair] through its two URIs as strings (empty string standing in
 * for a not-yet-picked reference, see [NoReferenceUriMarker]), so the last pair
 * captured this session survives a configuration change (e.g. rotation). */
private val ComparePairSaver: Saver<ComparePair?, List<String>> = Saver(
    save = { pair ->
        if (pair == null) {
            emptyList()
        } else {
            listOf(pair.processedUri.toString(), pair.referenceUri?.toString() ?: NoReferenceUriMarker)
        }
    },
    restore = { saved ->
        if (saved.size == 2) {
            ComparePair(
                processedUri = saved[0].toUri(),
                referenceUri = saved[1].takeIf { it != NoReferenceUriMarker }?.toUri(),
            )
        } else {
            null
        }
    },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val cameraSettings: CameraSettings = remember { SharedPreferencesCameraSettings(this) }
            var settings by remember { mutableStateOf(cameraSettings.load()) }
            var destination by rememberSaveable { mutableStateOf(AppDestination.Camera) }
            // Held in memory only (not persisted to disk) so the viewer can open
            // instantly after a capture; survives rotation via rememberSaveable.
            var comparePair by rememberSaveable(stateSaver = ComparePairSaver) {
                mutableStateOf<ComparePair?>(null)
            }
            var guidedStep by rememberSaveable { mutableStateOf(GuidedCompareStep.Idle) }
            val onGuidedEvent: (GuidedCompareEvent) -> Unit = { event ->
                guidedStep = GuidedCompareFlow.advance(guidedStep, event)
            }

            BackHandler(enabled = destination != AppDestination.Camera || guidedStep != GuidedCompareStep.Idle) {
                if (guidedStep != GuidedCompareStep.Idle) {
                    onGuidedEvent(GuidedCompareEvent.Cancelled)
                }
                if (destination != AppDestination.Camera) {
                    destination = AppDestination.Camera
                }
            }

            PocCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (destination) {
                        AppDestination.Camera -> CameraScreen(
                            modifier = Modifier.fillMaxSize(),
                            settings = settings,
                            onOpenSettings = { destination = AppDestination.Settings },
                            comparePair = comparePair,
                            onOpenCompare = { destination = AppDestination.Compare },
                            onComparePairCaptured = { comparePair = it },
                            guidedStep = guidedStep,
                            onGuidedCaptureCompleted = {
                                onGuidedEvent(GuidedCompareEvent.CaptureCompleted)
                                destination = AppDestination.Compare
                            },
                            onGuidedCancel = { onGuidedEvent(GuidedCompareEvent.Cancelled) },
                        )
                        AppDestination.Settings -> SettingsScreen(
                            modifier = Modifier.fillMaxSize(),
                            settings = settings,
                            onSettingsChanged = { updated ->
                                settings = updated
                                cameraSettings.save(updated)
                            },
                            onBack = { destination = AppDestination.Camera },
                        )
                        AppDestination.Compare -> CompareScreen(
                            modifier = Modifier.fillMaxSize(),
                            pair = comparePair,
                            onBack = { destination = AppDestination.Camera },
                            guidedStep = guidedStep,
                            onStartGuidedComparison = {
                                onGuidedEvent(GuidedCompareEvent.Start)
                                destination = AppDestination.Camera
                            },
                            onGuidedReferencePicked = { onGuidedEvent(GuidedCompareEvent.ReferencePicked) },
                            onGuidedCancel = { onGuidedEvent(GuidedCompareEvent.Cancelled) },
                        )
                    }
                }
            }
        }
    }
}
