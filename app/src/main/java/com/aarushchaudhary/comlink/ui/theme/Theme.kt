package com.aarushchaudhary.comlink.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "comlink_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val customAccentColorPref = intPreferencesKey("custom_accent_color")
val themeModePref = stringPreferencesKey("theme_mode")
val myDisplayNamePref = stringPreferencesKey("my_display_name")

@Composable
fun ComLinkTheme(
    context: Context,
    content: @Composable () -> Unit
) {
    val themeModeStr by context.dataStore.data.map { it[themeModePref] ?: ThemeMode.SYSTEM.name }
        .collectAsState(initial = ThemeMode.SYSTEM.name)
        
    val accentColorInt by context.dataStore.data.map { it[customAccentColorPref] ?: android.graphics.Color.GREEN }
        .collectAsState(initial = android.graphics.Color.GREEN)
    
    val themeMode = ThemeMode.valueOf(themeModeStr)
    val accentColor = Color(accentColorInt)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = accentColor, secondary = accentColor, background = Color.Black, surface = Color.Black)
    } else {
        lightColorScheme(primary = accentColor, secondary = accentColor, background = Color.Black, surface = Color.Black)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
