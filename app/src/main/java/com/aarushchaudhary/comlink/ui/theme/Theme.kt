package com.aarushchaudhary.comlink.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "comlink_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor(val color: Color) {
    CYPHERPUNK_GREEN(Color(0xFF00FF00)),
    ELECTRIC_CYAN(Color(0xFF00FFFF)),
    SUNSET_AMBER(Color(0xFFFF9900)),
    ROYAL_PURPLE(Color(0xFF9933FF)),
    CLASSIC_BLUE(Color(0xFF3366FF))
}

@Composable
fun ComLinkTheme(
    context: Context,
    content: @Composable () -> Unit
) {
    val themeModePref = stringPreferencesKey("theme_mode")
    val accentColorPref = stringPreferencesKey("accent_color")
    
    val themeModeStr by context.dataStore.data.map { it[themeModePref] ?: ThemeMode.SYSTEM.name }
        .collectAsState(initial = ThemeMode.SYSTEM.name)
        
    val accentColorStr by context.dataStore.data.map { it[accentColorPref] ?: AccentColor.CYPHERPUNK_GREEN.name }
        .collectAsState(initial = AccentColor.CYPHERPUNK_GREEN.name)
    
    val themeMode = ThemeMode.valueOf(themeModeStr)
    val accentColor = AccentColor.valueOf(accentColorStr)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = accentColor.color, secondary = accentColor.color)
    } else {
        lightColorScheme(primary = accentColor.color, secondary = accentColor.color)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
