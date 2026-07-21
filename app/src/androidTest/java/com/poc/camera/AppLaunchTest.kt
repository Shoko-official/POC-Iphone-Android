package com.poc.camera

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Permission-denied path (issue #92): a PermissionDeniedTest was attempted but dropped.
// CameraScreen's LaunchedEffect(Unit) launches the system permission dialog itself on
// first composition whenever permission isn't already granted (see CameraScreen.kt), so
// an instrumented test without GrantPermissionRule races that system dialog with no
// dependency-free way to dismiss it - UiAutomator would add a new dependency for a single
// test. The state-transition logic that produces the rationale/settings-prompt UI once a
// denial happens is already covered by CameraPermissionEvaluatorTest (a JVM unit test), so
// this stays a device-only gap rather than blocking the granted-path smoke coverage below.

/**
 * Smoke test for issue #92: the app launches to a bound, granted-permission viewfinder on
 * a real (or CI emulator) instrumentation process. Camera and audio permission are
 * pre-granted via [GrantPermissionRule] so MainActivity's own permission request never
 * shows a system dialog here - see the file-level comment above for why the denied path
 * isn't covered by an instrumented test.
 *
 * What this proves: the Compose tree reaches the granted-permission viewfinder chrome
 * (settings action, all four mode entries, shutter) on a real Activity/window, closing
 * this project's recurring "device-only, not verified" gap for the basic launch path.
 * What it does NOT prove: that the camera sensor actually streams frames to the preview
 * surface - see [CameraBindTest] for the closest available evidence of that.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun viewfinderChromeAppearsOnLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.open_settings_content_description))
            .assertExists()

        composeRule.onNodeWithText(context.getString(R.string.mode_photo)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.mode_portrait)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.mode_video)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.mode_cinematic)).assertExists()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.shutter_button_content_description))
            .assertExists()
    }
}
