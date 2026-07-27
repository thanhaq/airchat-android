package dev.offlinemesh.airchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF00856F),
    onPrimary = Color.White,
    secondary = Color(0xFF3451B2),
    tertiary = Color(0xFFB54708),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6E9EF),
    onSurface = Color(0xFF101828)
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF3DD6B4),
    onPrimary = Color(0xFF04231D),
    secondary = Color(0xFFAAB7FF),
    tertiary = Color(0xFFFFC48C),
    background = Color(0xFF101114),
    surface = Color(0xFF17191D),
    surfaceVariant = Color(0xFF2B3038),
    onSurface = Color(0xFFEDEFF4)
)

@Composable
fun AirChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
