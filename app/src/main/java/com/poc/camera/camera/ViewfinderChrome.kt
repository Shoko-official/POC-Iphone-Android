package com.poc.camera.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the camera viewfinder chrome's visual language (issue #82).
 * Before this, every overlay composable carried its own ad-hoc scrim alpha, corner radius,
 * padding and touch size, which is why the chrome drifted out of coherence over ~20 issues.
 * Everything visual now flows from here so the status chips, action buttons and the bottom
 * cluster share one look.
 *
 * The chrome is deliberately quiet: a single translucent black scrim, white content, one
 * corner radius, one stroke language ([CameraGlyphs]). White-on-scrim over an unpredictable
 * live preview (sky, snow, direct light) can't be guaranteed to clear WCAG AA on its own -
 * the preview underneath is arbitrary - so [TextShadow] and the icon buttons' solid scrim
 * backing carry legibility for the worst-case near-white frame, the same mitigation the
 * original chrome relied on.
 */
internal object ChromeTokens {
    val Scrim = Color.Black.copy(alpha = 0.55f)
    val TextShadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 4f)

    val ChipCornerRadius = 10.dp
    val ChipShape = RoundedCornerShape(ChipCornerRadius)

    /** Screen-edge inset shared by the top bar and bottom cluster. */
    val EdgeInset = 16.dp

    /** Vertical gap between stacked status chips. */
    val ChipSpacing = 8.dp

    /** Gap between adjacent action icon buttons. */
    val ActionSpacing = 4.dp

    /** Gap between the mode selector and the shutter row. */
    val ClusterSpacing = 16.dp

    /** Internal padding of a status chip. */
    val ChipPaddingHorizontal = 10.dp
    val ChipPaddingVertical = 6.dp

    /** Content gap inside a chip (e.g. the recording dot and its timer). */
    val ChipContentSpacing = 6.dp

    val TouchTarget = 48.dp
    val IconSize = 22.dp

    val OnChrome = Color.White

    /** Icon colour on an active (inverted) action button - dark on the white fill. */
    val OnActive = Color.Black
    val DisabledAlpha = 0.38f
}

/** Shared text style for every chrome label: quiet, white, shadowed for legibility. */
@Composable
internal fun chromeTextStyle(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(
        color = ChromeTokens.OnChrome,
        shadow = ChromeTokens.TextShadow,
    )

/**
 * Passive status chip: a compact scrim-backed row for read-only viewfinder status (recording
 * timer, HLG/quality, cinematic config, zoom). Not a touch target itself - callers that need
 * one (the zoom-reset chip) wrap it in their own 48dp clickable area so every chip keeps the
 * same visual height regardless of whether it happens to be interactive.
 */
@Composable
internal fun StatusChip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .background(ChromeTokens.Scrim, ChromeTokens.ChipShape)
            .padding(
                horizontal = ChromeTokens.ChipPaddingHorizontal,
                vertical = ChromeTokens.ChipPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChromeTokens.ChipContentSpacing),
        content = content,
    )
}

/** Convenience overload for the common text-only status chip. */
@Composable
internal fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    StatusChip(modifier = modifier) {
        Text(
            text = text,
            style = chromeTextStyle(),
            modifier = contentDescription?.let { desc ->
                Modifier.semantics { this.contentDescription = desc }
            } ?: Modifier,
        )
    }
}

/**
 * Action icon button: the one interactive-control shape on the chrome - a 48dp circular
 * scrim carrying a 22dp [CameraGlyphs] icon. [active] inverts it (white fill, dark icon) as
 * a non-colour state cue for toggles like the torch; [enabled] dims the icon to
 * [ChromeTokens.DisabledAlpha] the same way the rest of the screen signals a disabled
 * control. The icon's own [contentDescription] is null - the semantics live on the button.
 */
@Composable
internal fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(ChromeTokens.TouchTarget)
            .background(if (active) ChromeTokens.OnChrome else ChromeTokens.Scrim, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) ChromeTokens.OnActive else ChromeTokens.OnChrome
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (enabled) 1f else ChromeTokens.DisabledAlpha),
            modifier = Modifier.size(ChromeTokens.IconSize),
        )
    }
}

// --- Dev-only references (issue #82). No runtime cost; a visual catalogue for future work. ---

@Preview(name = "Camera glyphs", showBackground = true, backgroundColor = 0xFF202124)
@Composable
private fun CameraGlyphsPreview() {
    val glyphs = listOf(
        "flash-off" to CameraGlyphs.FlashOff,
        "flash-auto" to CameraGlyphs.FlashAuto,
        "flash-on" to CameraGlyphs.FlashOn,
        "torch" to CameraGlyphs.Torch,
        "flip" to CameraGlyphs.FlipCamera,
        "compare" to CameraGlyphs.Compare,
        "burst" to CameraGlyphs.Burst,
        "look" to CameraGlyphs.Look,
        "settings" to CameraGlyphs.Settings,
    )
    Column(
        modifier = Modifier.padding(ChromeTokens.EdgeInset),
        verticalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing),
    ) {
        glyphs.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing)) {
                row.forEach { (label, glyph) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ActionIconButton(icon = glyph, contentDescription = label, onClick = {})
                        Text(text = label, style = chromeTextStyle())
                    }
                }
            }
        }
    }
}

@Preview(name = "Chrome styles", showBackground = true, backgroundColor = 0xFF202124)
@Composable
private fun ChromeStylesPreview() {
    Column(
        modifier = Modifier.padding(ChromeTokens.EdgeInset),
        verticalArrangement = Arrangement.spacedBy(ChromeTokens.ChipSpacing),
    ) {
        StatusChip {
            Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
            Text(text = "0:07", style = chromeTextStyle())
        }
        StatusChip(text = "HLG10 - 1080p")
        StatusChip(text = "2.0x")
        Row(horizontalArrangement = Arrangement.spacedBy(ChromeTokens.ActionSpacing)) {
            ActionIconButton(icon = CameraGlyphs.FlashAuto, contentDescription = "flash", onClick = {})
            ActionIconButton(icon = CameraGlyphs.Torch, contentDescription = "torch on", active = true, onClick = {})
            ActionIconButton(icon = CameraGlyphs.Settings, contentDescription = "settings", enabled = false, onClick = {})
        }
    }
}
