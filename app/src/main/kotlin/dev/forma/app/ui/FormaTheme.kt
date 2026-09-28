package dev.forma.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF70DAC4), onPrimary = Color(0xFF00382E),
    secondary = Color(0xFFBCCBC6), background = Color(0xFF10181B),
    surface = Color(0xFF141D20), surfaceVariant = Color(0xFF253136),
    onBackground = Color(0xFFE2ECE9), onSurface = Color(0xFFE2ECE9)
)
private val Light = lightColorScheme(
    primary = Color(0xFF006B58), onPrimary = Color.White,
    secondary = Color(0xFF49645C), background = Color(0xFFF5F9F7),
    surface = Color(0xFFFAFDFC), surfaceVariant = Color(0xFFE4EEE9)
)
@Composable fun FormaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
