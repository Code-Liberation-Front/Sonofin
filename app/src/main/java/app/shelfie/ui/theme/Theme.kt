package app.shelfie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SonofinPurple = Color(0xFFA855F7)
val SonofinPurpleDim = Color(0xFF7C3AED)
val SonofinBackground = Color(0xFF141019)
val SonofinSurface = Color(0xFF1E1827)
val SonofinSurfaceHigh = Color(0xFF2A2136)

private val DarkColors = darkColorScheme(
    primary = SonofinPurple,
    onPrimary = Color.White,
    primaryContainer = SonofinPurpleDim,
    onPrimaryContainer = Color.White,
    secondary = SonofinPurple,
    onSecondary = Color.White,
    background = SonofinBackground,
    onBackground = Color(0xFFE9E6F0),
    surface = SonofinSurface,
    onSurface = Color(0xFFE9E6F0),
    surfaceVariant = SonofinSurfaceHigh,
    onSurfaceVariant = Color(0xFFB7AEC6),
)

@Composable
fun ShelfieTheme(content: @Composable () -> Unit) {
    // Always dark, purple-accented.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
