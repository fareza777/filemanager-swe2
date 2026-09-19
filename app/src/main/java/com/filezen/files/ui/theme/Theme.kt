package com.filezen.files.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filezen.files.data.prefs.ThemeMode

// Deep teal + warm amber — calm premium file-manager palette.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9DF2DC),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF3F6368),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E8EF),
    onSecondaryContainer = Color(0xFF051F24),
    tertiary = Color(0xFF7A5B13),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEDF8A),
    onTertiaryContainer = Color(0xFF261A00),
    surface = Color(0xFFF7FAF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    background = Color(0xFFF7FAF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5F4),
    surfaceContainer = Color(0xFFEBF0EE),
    surfaceContainerHigh = Color(0xFFE5EAE8),
    surfaceContainerHighest = Color(0xFFDFE4E2),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C5),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81D5C0),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = Color(0xFF9DF2DC),
    secondary = Color(0xFFA6CDD4),
    onSecondary = Color(0xFF0E353B),
    secondaryContainer = Color(0xFF274B50),
    onSecondaryContainer = Color(0xFFC2E8EF),
    tertiary = Color(0xFFEFC36D),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A430A),
    onTertiaryContainer = Color(0xFFFEDF8A),
    surface = Color(0xFF0D1211),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    background = Color(0xFF0D1211),
    surfaceContainerLowest = Color(0xFF080D0C),
    surfaceContainerLow = Color(0xFF161B1A),
    surfaceContainer = Color(0xFF1A1F1E),
    surfaceContainerHigh = Color(0xFF242A28),
    surfaceContainerHighest = Color(0xFF2F3533),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

private val ZenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
    MaterialTheme(colorScheme = colors, shapes = ZenShapes, content = content)
}
