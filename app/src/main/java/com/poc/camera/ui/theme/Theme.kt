package com.poc.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HeroUI v3 dark palette, mapped to Material 3 color roles.
 *
 * Values are the sRGB projections of HeroUI v3's dark-theme oklch variables
 * (`@heroui/styles` v3.0.5): background oklch(12% 0.005 285.8), surface/eclipse
 * oklch(0.2103 0.0059 285.9), accent oklch(0.6204 0.195 253.8), danger
 * oklch(0.594 0.1967 24.6), warning oklch(0.8203 0.1388 76.3), muted
 * oklch(70.5% 0.015 286.1), segment oklch(0.3964 0.01 285.9), border
 * oklch(28% 0.006 286.0), foreground/snow oklch(0.9911 0 0).
 *
 * Role mapping keeps M3 component semantics intact so every Material component
 * (segmented buttons, switches, sliders, dialogs) picks the HeroUI look up
 * automatically: `secondaryContainer` is HeroUI's raised segment thumb,
 * `surfaceVariant`/`onSurfaceVariant` are its secondary surface + muted text,
 * `outline`/`outlineVariant` its border + separator.
 */
internal object HeroUiColors {
    val Background = Color(0xFF131316)
    val Foreground = Color(0xFFFCFCFC)
    val Surface = Color(0xFF27272A)
    val SurfaceSecondary = Color(0xFF303033)
    val Muted = Color(0xFFA7A7B0)
    val Segment = Color(0xFF4A4A50)
    val Accent = Color(0xFF3B82F6)
    val Danger = Color(0xFFE5484D)
    val Warning = Color(0xFFF5A524)
    val Border = Color(0xFF35353A)
    val Separator = Color(0xFF2E2E32)
}

private val HeroUiDarkColorScheme = darkColorScheme(
    primary = HeroUiColors.Accent,
    onPrimary = HeroUiColors.Foreground,
    primaryContainer = HeroUiColors.Accent.copy(alpha = 0.15f),
    onPrimaryContainer = HeroUiColors.Foreground,
    secondary = HeroUiColors.Muted,
    onSecondary = HeroUiColors.Background,
    secondaryContainer = HeroUiColors.Segment,
    onSecondaryContainer = HeroUiColors.Foreground,
    tertiary = HeroUiColors.Warning,
    onTertiary = HeroUiColors.Background,
    background = HeroUiColors.Background,
    onBackground = HeroUiColors.Foreground,
    surface = HeroUiColors.Background,
    onSurface = HeroUiColors.Foreground,
    surfaceVariant = HeroUiColors.SurfaceSecondary,
    onSurfaceVariant = HeroUiColors.Muted,
    surfaceContainer = HeroUiColors.Surface,
    surfaceContainerHigh = HeroUiColors.SurfaceSecondary,
    surfaceContainerHighest = HeroUiColors.Segment,
    error = HeroUiColors.Danger,
    onError = HeroUiColors.Foreground,
    outline = HeroUiColors.Border,
    outlineVariant = HeroUiColors.Separator,
)

/**
 * The camera screen is an immersive viewfinder, so the app always runs a dark
 * scheme regardless of the system theme setting - a light chrome would wash out
 * and compete with the live preview. The scheme is the fixed HeroUI v3 dark
 * palette above: a deliberate brand identity, so system dynamic color no longer
 * overrides it (it previously produced an arbitrary per-wallpaper look).
 */
@Composable
fun PocCameraTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = HeroUiDarkColorScheme,
        typography = Typography(),
        content = content,
    )
}
