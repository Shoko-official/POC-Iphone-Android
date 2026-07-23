package com.poc.camera

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for issue #92: settings navigation and mode switching, granted-permission
 * path only (see [AppLaunchTest]'s file-level comment for why the denied path is not
 * covered by an instrumented test).
 *
 * What this proves: MainActivity's [AppDestination] navigation and CameraScreen's mode
 * state actually wire up end to end in a real Compose tree - tapping the settings gear
 * reaches SettingsScreen and back navigates to the viewfinder again, and switching
 * [com.poc.camera.camera.CameraMode] changes the rendered shutter affordance. What it
 * does NOT prove: that any individual setting control persists correctly (covered by
 * CameraSettingsData's own unit tests) or that the camera actually rebinds its use cases
 * on a mode switch on real hardware - see [CameraBindTest].
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsGearOpensAndBackReturnsToViewfinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.open_settings_content_description))
            .performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.settings_section_burst))
            .assertExists()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings_back_content_description))
            .performClick()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.open_settings_content_description))
            .assertExists()
    }

    @Test
    fun modeSwitchChangesShutterAffordance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Photo (the default mode) and Portrait share the still-capture affordance -
        // see CameraMode.isVideoLike.
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.shutter_button_content_description))
            .assertExists()

        composeRule.onNodeWithText(context.getString(R.string.mode_portrait)).performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.shutter_button_content_description))
            .assertExists()

        composeRule.onNodeWithText(context.getString(R.string.mode_video)).performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.record_button_content_description))
            .assertExists()
    }

    /**
     * Regression for issue #143 (Bug 1): CameraScreen's `rememberSaveable` state must survive
     * a Settings round trip. Before the fix, MainActivity's `when (destination)` disposed and
     * rebuilt the whole CameraScreen subtree on every navigation, and Compose only restores
     * `rememberSaveable` state on an OS-driven save (config change / process death), never on
     * an ordinary disposal - so the selected [com.poc.camera.camera.CameraMode] silently reset
     * to the Photo default on returning from Settings. The `SaveableStateProvider` wrapper now
     * preserves it. Mode is used as the assertable proxy for that state (a deterministic tap,
     * unlike pinch-zoom); its record affordance is a pure function of mode, so this stays
     * flake-resistant and independent of any real camera bind on the emulator.
     */
    @Test
    fun cameraModeSurvivesSettingsRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Move off the Photo default into Video, whose distinct record affordance is a
        // deterministic, camera-independent signal of the current mode.
        composeRule.onNodeWithText(context.getString(R.string.mode_video)).performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.record_button_content_description))
            .assertExists()

        // Round-trip through Settings and back.
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.open_settings_content_description))
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.settings_section_burst))
            .assertExists()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings_back_content_description))
            .performClick()

        // The mode must have survived: the record affordance is still shown, not the photo
        // shutter it would revert to if the subtree had reset to defaults.
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.record_button_content_description))
            .assertExists()
    }
}
