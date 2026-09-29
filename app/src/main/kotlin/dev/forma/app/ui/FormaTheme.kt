package dev.forma.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.forma.core.settings.PreferenceValues
import dev.forma.core.settings.SettingValue

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
/** Theme preview is scoped by the caller; production changes only follow a successful preference save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FormaTheme(preferences: PreferenceValues = PreferenceValues.EMPTY, content: @Composable () -> Unit) {
    fun choice(id: String, fallback: String) = (preferences[id] as? SettingValue.Choice)?.value ?: fallback
    val mode=choice("ui.theme","system")
    val dark=mode == "dark" || mode == "black" || mode == "system" && isSystemInDarkTheme()
    val accent=choice("ui.accent","mint")
    var colors=if (accent == "dynamic" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) Dark else Light
    val pair = when (accent) {
        "blue" -> if (dark) Color(0xFFAECBFA) to Color(0xFF001D35) else Color(0xFF195CA8) to Color.White
        "violet" -> if (dark) Color(0xFFD0BCFF) to Color(0xFF2B1763) else Color(0xFF6750A4) to Color.White
        "amber" -> if (dark) Color(0xFFF5D477) to Color(0xFF3F2E00) else Color(0xFF795900) to Color.White
        else -> colors.primary to colors.onPrimary
    }
    colors=colors.copy(primary=pair.first,onPrimary=pair.second)
    if (mode == "black") colors=colors.copy(background=Color.Black,surface=Color.Black,surfaceVariant=Color(0xFF161B1C))
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 52.dp) {
        MaterialTheme(colorScheme=colors,content=content)
    }
}
