package com.poc.camera

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// An actual capture-with-segmentation test (portraitCaptureFallsBackGracefully) was
// deliberately NOT added for issue #136. The reasoning:
//
// - CameraBindTest's own doc comment already establishes this project's position on the
//   emulator's virtual camera: binding is verified (issue #92), but "that a capture
//   produces a usable JPEG" is explicitly called out as staying a "device-hardware-only"
//   gap. startPortraitCapture (CameraScreen.kt) needs BurstController to successfully
//   acquire and decode an entire burst of real JPEGs via ImageCapture before segmentation
//   ever runs - so this test would be exercising exactly the capture reliability the
//   project already disclaims, not just the (bundled, offline) MLKit no-subject fallback
//   on top of it.
// - Even if capture reliably succeeded, segmentation adds its own internal ~3s timeout on
//   top of burst/merge/finish/save, and the eventual result surfaces only as a Snackbar,
//   which auto-dismisses - a bounded poll can miss it by design, not just by bad luck.
// - This project's local CI is the real gate for anything hardware-shaped (issue #92); a
//   test that cannot be run and observed locally first is exactly the kind that should not
//   be guessed into the suite - a flaky instrumented test is worse than no test.
//
// What IS covered below is the part of Portrait's flow that is deterministic in Compose's
// own test tree: the mode switch, the shutter's resulting content description, and the
// secondary-control slot layout - all synchronous UI state, no camera hardware involved.

/**
 * Instrumented coverage for issue #136: Portrait mode's viewfinder chrome, which
 * [NavigationTest.modeSwitchChangesShutterAffordance] only brushes past (it checks the
 * shutter keeps *a* content description when switching into Portrait, not which one, and
 * says nothing about the secondary control slots either side of it).
 *
 * What this proves: Portrait is photo-like end to end in the chrome - selecting it selects
 * the Portrait segmented entry (not Photo), keeps the same still-capture shutter content
 * description Photo uses (see [com.poc.camera.camera.CameraMode.isVideoLike], false for
 * Portrait), and leaves both secondary slots the way Photo's now do since issue #101 removed
 * Photo's own Burst chip - the Look toggle (Cinematic-only) is absent in Portrait exactly as
 * it already is in Photo. Switching back to Photo restores the same shutter affordance and
 * selection. What it does NOT prove: see the file-level note above for the capture/
 * segmentation gap this class leaves undone, on purpose.
 */
@RunWith(AndroidJUnit4::class)
class PortraitModeTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun portraitModeSharesPhotoShutterAndEmptySecondarySlots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shutterDescription = context.getString(R.string.shutter_button_content_description)
        val photoLabel = context.getString(R.string.mode_photo)
        val portraitLabel = context.getString(R.string.mode_portrait)
        // Both possible Look-toggle content descriptions (the label they carry mirrors
        // whichever VideoLook is currently active) - see LookToggleButton in CameraScreen.kt.
        // Checked regardless of the persisted default look, since the toggle is only ever
        // rendered in Cinematic mode and must be absent here no matter which label it would
        // otherwise carry.
        val lookToggleTemplate = context.getString(R.string.look_toggle_content_description)
        val neutralLookDescription = String.format(
            lookToggleTemplate,
            context.getString(R.string.look_neutral),
        )
        val cinematicLookDescription = String.format(
            lookToggleTemplate,
            context.getString(R.string.look_cinematic_short),
        )

        composeRule.onNodeWithText(portraitLabel).performClick()

        composeRule.onNodeWithText(portraitLabel).assertIsSelected()
        composeRule.onNodeWithText(photoLabel).assertIsNotSelected()

        composeRule
            .onNodeWithContentDescription(shutterDescription)
            .assertExists()

        composeRule.onAllNodesWithContentDescription(neutralLookDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(cinematicLookDescription).assertCountEquals(0)

        // Switching back to Photo restores the same shutter affordance and selection.
        composeRule.onNodeWithText(photoLabel).performClick()

        composeRule.onNodeWithText(photoLabel).assertIsSelected()
        composeRule.onNodeWithText(portraitLabel).assertIsNotSelected()

        composeRule
            .onNodeWithContentDescription(shutterDescription)
            .assertExists()
    }
}
