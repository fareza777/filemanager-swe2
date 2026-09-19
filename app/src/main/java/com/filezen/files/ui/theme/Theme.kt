package com.filezen.files.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filezen.files.data.prefs.ThemeAccent
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

// Accent variants — (light primary, light primaryContainer, dark primary, dark primaryContainer)
private data class AccentColors(
    val lp: Long, val lpc: Long, val dp: Long, val dpc: Long,
    val ltert: Long, val dtert: Long,
)

private val Accents = mapOf(
    ThemeAccent.TEAL to AccentColors(
        0xFF006B5D, 0xFF9DF2DC, 0xFF81D5C0, 0xFF005144, 0xFF7A5B13, 0xFFEFC36D),
    ThemeAccent.SUNSET to AccentColors(
        0xFF9A4520, 0xFFFFDBCE, 0xFFFFB59B, 0xFF772F0D, 0xFF00554A, 0xFF6FDBBD),
    ThemeAccent.VIOLET to AccentColors(
        0xFF6750A4, 0xFFEADDFF, 0xFFD0BCFF, 0xFF4F378B, 0xFF9A4520, 0xFFFFB59B),
    ThemeAccent.OCEAN to AccentColors(
        0xFF0061A4, 0xFFD1E4FF, 0xFF9ECAFF, 0xFF00497D, 0xFF7A5B13, 0xFFEFC36D),
    ThemeAccent.ROSE to AccentColors(
        0xFF9C4055, 0xFFFFD9E0, 0xFFFFB1C0, 0xFF7D293A, 0xFF00554A, 0xFF6FDBBD),
)

private val ZenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FileZenTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: ThemeAccent = ThemeAccent.TEAL,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (accent == ThemeAccent.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val a = Accents[accent] ?: Accents.getValue(ThemeAccent.TEAL)
        val base = if (dark) DarkColors else LightColors
        base.copy(
            primary = Color(if (dark) a.dp else a.lp),
            primaryContainer = Color(if (dark) a.dpc else a.lpc),
            tertiary = Color(if (dark) a.dtert else a.ltert),
        )
    }
    MaterialTheme(colorScheme = colors, shapes = ZenShapes, content = content)
}
