package com.poc.camera

import android.Manifest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val BIND_POLL_WINDOW_MILLIS = 10_000L
private const val BIND_POLL_INTERVAL_MILLIS = 500L

/**
 * Smoke test for issue #92: the closest instrumented evidence this suite has that
 * CameraX actually bound to the emulator's virtual camera, rather than only that the
 * granted-permission Compose branch rendered (see [AppLaunchTest]).
 *
 * Whether the flash unit is present (`hasFlashUnit`) is too device-dependent to assert on
 * an emulator, and CameraX doesn't expose a simple "bound and streaming" flag to poll -
 * so this instead polls, for a bounded window, that CameraScreen's `onBindError` path
 * (see CameraScreen.kt) never surfaces its failure card. A real bind failure throws or
 * fails asynchronously well within this window on every device this project targets.
 *
 * What this proves: within [BIND_POLL_WINDOW_MILLIS] of launch, CameraX's
 * `bindToLifecycle` for the current mode's use cases did not fail on the emulator's
 * virtual camera, and the shutter stays a live, enabled control. What it does NOT prove:
 * that the bound camera streams real frames to the PreviewView surface, that autofocus/
 * autoexposure converge, or that a capture produces a usable JPEG - the emulator's
 * virtual camera has no real sensor behind it, so those stay device-hardware-only per
 * this project's recurring verification gap.
 */
@RunWith(AndroidJUnit4::class)
class CameraBindTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cameraBindsWithoutErrorWithinBoundedWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bindFailureMessage = context.getString(R.string.preview_bind_failure_message)
        val shutterDescription = context.getString(R.string.shutter_button_content_description)

        val deadline = System.currentTimeMillis() + BIND_POLL_WINDOW_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val bindFailed = composeRule
                .onAllNodesWithText(bindFailureMessage)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (bindFailed) {
                throw AssertionError("Camera preview reported a bind failure: $bindFailureMessage")
            }
            Thread.sleep(BIND_POLL_INTERVAL_MILLIS)
        }

        composeRule
            .onNodeWithContentDescription(shutterDescription)
            .assertExists()
            .assertIsEnabled()
    }
}
