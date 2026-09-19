package com.filezen.files.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.filezen.files.data.prefs.ThemeMode

private val Seed = Color(0xFF0E7C66)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0E1),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A6360),
    secondaryContainer = Color(0xFFCCE8E4),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDCE5E2),
    background = Color(0xFFF7FAF9),
    surfaceContainerLow = Color(0xFFF0F5F4),
    surfaceContainer = Color(0xFFEBF1EF),
    surfaceContainerHigh = Color(0xFFE5EBE9),
    surfaceContainerHighest = Color(0xFFDFE6E4),
    outline = Color(0xFF6F7977),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD6C5),
    onPrimary = Color(0xFF003832),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB9F0E1),
    secondary = Color(0xFFB0CCC9),
    secondaryContainer = Color(0xFF324B48),
    surface = Color(0xFF0F1514),
    surfaceVariant = Color(0xFF3F4947),
    background = Color(0xFF0F1514),
    surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
    outline = Color(0xFF899391),
)

@Composable
fun FileZenTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
