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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.poc.camera.camera.CameraScreen
import com.poc.camera.settings.CameraSettings
import com.poc.camera.settings.SettingsScreen
import com.poc.camera.settings.SharedPreferencesCameraSettings
import com.poc.camera.ui.theme.PocCameraTheme

private enum class AppDestination {
    Camera,
    Settings,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val cameraSettings: CameraSettings = remember { SharedPreferencesCameraSettings(this) }
            var settings by remember { mutableStateOf(cameraSettings.load()) }
            var destination by rememberSaveable { mutableStateOf(AppDestination.Camera) }

            BackHandler(enabled = destination == AppDestination.Settings) {
                destination = AppDestination.Camera
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
                    }
                }
            }
        }
    }
}
