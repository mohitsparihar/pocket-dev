package dev.pocket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class PocketAppearance { Dark, Light, System }

private val DarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = Charcoal,
    secondary = Indigo,
    background = Charcoal,
    onBackground = Ink,
    surface = CharcoalRaised,
    onSurface = Ink,
    surfaceVariant = CharcoalCard,
    onSurfaceVariant = InkMuted,
    outline = Line,
    outlineVariant = LineSoft,
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = IndigoDeep,
    onPrimary = Paper,
    secondary = IndigoDeep,
    background = Paper,
    onBackground = InkOnPaper,
    surface = PaperCard,
    onSurface = InkOnPaper,
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = InkMutedOnPaper,
    outline = Color(0xFFB4BCC8),
    outlineVariant = LineOnPaper,
    error = Danger,
)

@Composable
fun PocketTheme(
    appearance: PocketAppearance = PocketAppearance.Dark,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        PocketAppearance.Dark -> true
        PocketAppearance.Light -> false
        PocketAppearance.System -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PocketTypography,
        content = content,
    )
}
