package com.quicklock.applock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// One UI-inspired: soft grey canvas + white rounded cards in light mode, true-black OLED canvas in dark mode,
// a single blue accent, large corner radii. Static colours (no dynamic color) for a consistent Samsung look.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3E91FF),
    onPrimary = Color.White,
    background = Color(0xFFF6F6F6),
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF6D6D6D),
    error = Color(0xFFE5484D),
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0A3A7A),
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF7A1015),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BA4FF),
    onPrimary = Color(0xFF00172F),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9AA0),
    error = Color(0xFFFF6B70),
    primaryContainer = Color(0xFF12315C),
    onPrimaryContainer = Color(0xFFD6E7FF),
    errorContainer = Color(0xFF4A1A1D),
    onErrorContainer = Color(0xFFFFD9DA),
)

@Composable
fun QuickLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
