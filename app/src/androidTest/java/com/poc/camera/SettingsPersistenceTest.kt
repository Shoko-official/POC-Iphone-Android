package com.poc.camera

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poc.camera.settings.SettingsTestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Mirrors SharedPreferencesCameraSettings.PREFS_NAME (private there, so duplicated here) -
// the only way an instrumented test can reach the real on-device prefs file to reset it.
private const val CAMERA_SETTINGS_PREFS_NAME = "camera_settings"

/**
 * Instrumented coverage for issue #136: settings actually round-trip through real Android
 * [android.content.SharedPreferences] via
 * [com.poc.camera.settings.SharedPreferencesCameraSettings], not just through in-memory
 * Compose state - a gap [NavigationTest]'s own doc explicitly leaves open ("that any
 * individual setting control persists correctly" is attributed to "CameraSettingsData's own
 * unit tests", but those are plain JVM tests against
 * [com.poc.camera.settings.CameraSettingsData.fromRaw] and raw fields directly, never
 * against a real Context-backed SharedPreferences instance).
 *
 * SharedPreferences on the instrumented target process outlives any single test method, so
 * [resetPersistedSettings] clears the file before every test. MainActivity only reads
 * settings once, via `remember { cameraSettings.load() }` at its first composition (see
 * MainActivity.kt) - and that composition already happened by the time `@Before` runs
 * ([androidx.compose.ui.test.junit4.createAndroidComposeRule]'s own doc: the Activity is
 * launched by its `activityRule` "before the test starts") - so clearing the file alone
 * would not be reflected in the already-composed `settings` state. `resetPersistedSettings`
 * forces an Activity recreation right after clearing, which re-runs `onCreate` and reloads
 * settings from the now-empty file, giving every test a deterministic
 * [com.poc.camera.settings.CameraSettingsData.DEFAULT] starting point.
 *
 * Node addressing uses [SettingsTestTags] rather than label text: an earlier text-based
 * revision of this test (`onNodeWithText(vividLabel).performClick()`, relying on the merged
 * semantics tree collapsing the label into its parent SegmentedButton) clicked correctly for
 * reading afterwards but did not reliably drive the SegmentedButton's own click/toggle action
 * on the CI emulator - see [SettingsTestTags]'s own doc in SettingsScreen.kt. Every click and
 * assertion below targets the exact tagged node that carries the click action and the
 * selected/checked state, removing that ambiguity.
 *
 * What this proves: toggling the Photo look preset to Vivid and switching HDR burst and
 * Night mode on survives backing out to the viewfinder and reopening Settings (in-app
 * navigation), AND survives a full Activity recreation - the closest this suite can get to
 * an app-process restart - reading the same three values back from
 * [com.poc.camera.settings.SharedPreferencesCameraSettings.load] instead of carried-over
 * in-memory state. What it does NOT prove: persistence across an actual process death (
 * [androidx.test.core.app.ActivityScenario.recreate] destroys and recreates the Activity,
 * not the process).
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetPersistedSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(CAMERA_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun presetAndSwitchTogglesPersistAcrossNavigationAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val openSettingsDescription = context.getString(R.string.open_settings_content_description)
        val backDescription = context.getString(R.string.settings_back_content_description)

        composeRule.onNodeWithContentDescription(openSettingsDescription).performClick()

        // Sanity check: resetPersistedSettings actually landed on CameraSettingsData.DEFAULT
        // before this test's own toggles below - a clear failure here means the reset
        // itself is broken, not the persistence this test otherwise targets.
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_NATURAL).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_HDR_BURST).assertIsOff()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_NIGHT_MODE).assertIsOff()

        composeRule.onNodeWithTag(SettingsTestTags.PRESET_VIVID).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_HDR_BURST).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_NIGHT_MODE).performClick()

        composeRule.onNodeWithTag(SettingsTestTags.PRESET_VIVID).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_NATURAL).assertIsNotSelected()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_HDR_BURST).assertIsOn()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_NIGHT_MODE).assertIsOn()

        // Back out to the viewfinder and reopen Settings: the literal "navigate back;
        // reopen settings" case - MainActivity keeps one shared `settings` var across
        // destinations, so this mainly guards against SettingsScreen resetting state on
        // recomposition, ahead of the stronger recreation check below.
        composeRule.onNodeWithContentDescription(backDescription).performClick()
        composeRule.onNodeWithContentDescription(openSettingsDescription).assertExists()

        composeRule.onNodeWithContentDescription(openSettingsDescription).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_VIVID).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_NATURAL).assertIsNotSelected()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_HDR_BURST).assertIsOn()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_NIGHT_MODE).assertIsOn()

        // Land back on the viewfinder before recreating, so the post-recreate state is
        // unambiguous regardless of how `destination` (rememberSaveable in MainActivity)
        // round-trips through the recreation, then explicitly reopen Settings afterward.
        composeRule.onNodeWithContentDescription(backDescription).performClick()
        composeRule.onNodeWithContentDescription(openSettingsDescription).assertExists()

        // The real persistence proof: recreate the Activity so its `settings` state is
        // re-read from SharedPreferencesCameraSettings.load() fresh, rather than merely
        // surviving inside the same in-memory Compose state across a screen swap.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(openSettingsDescription).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_VIVID).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTestTags.PRESET_NATURAL).assertIsNotSelected()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_HDR_BURST).assertIsOn()
        composeRule.onNodeWithTag(SettingsTestTags.SWITCH_NIGHT_MODE).assertIsOn()
    }
}
