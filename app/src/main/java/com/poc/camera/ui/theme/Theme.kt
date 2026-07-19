package com.poc.camera.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    secondary = Color(0xFFBFC6DC),
    tertiary = Color(0xFFDEBCDF),
)

/**
 * The camera screen is an immersive viewfinder, so the app always runs the dark
 * Material 3 scheme regardless of the system theme setting - a light chrome would
 * wash out and compete with the live preview. Dynamic color is still honoured where
 * available, using its dark variant.
 */
@Composable
fun PocCameraTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
