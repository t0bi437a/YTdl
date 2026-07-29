package com.ytdl.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ytdl.app.settings.ThemeMode

private val Red = Color(0xFFC62828)
private val RedDark = Color(0xFFFF7A73)

private val LightColors = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Color(0xFF7A5C5C),
    background = Color(0xFFFDF7F6),
    surface = Color(0xFFFDF7F6),
)

private val DarkColors = darkColorScheme(
    primary = RedDark,
    onPrimary = Color(0xFF5F1412),
    secondary = Color(0xFFE7BDBA),
    background = Color(0xFF101014),
    surface = Color(0xFF14141A),
)

@Composable
fun YTdlTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
