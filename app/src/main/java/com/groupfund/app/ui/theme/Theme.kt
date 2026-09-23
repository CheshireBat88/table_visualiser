package com.groupfund.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF002200),
    secondary = Color(0xFF388E3C),
    onSecondary = Color.White,
    background = Color(0xFFFDFDF6),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFDFDF6),
    onSurface = Color(0xFF1A1C19),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF0E5B2E),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color(0xFF00391F),
    background = Color(0xFF12140F),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF1A1C19),
    onSurface = Color(0xFFE2E3DA),
)

@Composable
fun GroupFundTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}