package com.aarushchaudhary.comlink.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aarushchaudhary.comlink.ui.theme.AccentColor
import com.aarushchaudhary.comlink.ui.theme.ThemeMode
import com.aarushchaudhary.comlink.ui.theme.dataStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val themeModePref = stringPreferencesKey("theme_mode")
    val accentColorPref = stringPreferencesKey("accent_color")

    var currentThemeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var currentAccentColor by remember { mutableStateOf(AccentColor.CYPHERPUNK_GREEN) }

    LaunchedEffect(Unit) {
        context.dataStore.data.collect { prefs ->
            currentThemeMode = ThemeMode.valueOf(prefs[themeModePref] ?: ThemeMode.SYSTEM.name)
            currentAccentColor = AccentColor.valueOf(prefs[accentColorPref] ?: AccentColor.CYPHERPUNK_GREEN.name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Theme Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ThemeMode.values().forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch { context.dataStore.edit { it[themeModePref] = mode.name } }
                    }
                ) {
                    RadioButton(
                        selected = currentThemeMode == mode,
                        onClick = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }

            Spacer(Modifier.height(32.dp))
            
            Text("Accent Color", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AccentColor.values().forEach { colorEnum ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colorEnum.color)
                            .clickable {
                                scope.launch { context.dataStore.edit { it[accentColorPref] = colorEnum.name } }
                            }
                    ) {
                        if (currentAccentColor == colorEnum) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center).size(20.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onPrimary
                            ) {}
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            
            Text("About Developer & Project", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ComLink", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Version 1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    
                    val intentContext = LocalContext.current
                    
                    ListItem(
                        headlineContent = { Text("Aarush Chaudhary") },
                        supportingContent = { Text("Developer") },
                        leadingContent = { Icon(Icons.Default.Person, "Developer") },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text("LinkedIn") },
                        leadingContent = { Icon(Icons.Default.Link, "LinkedIn") },
                        modifier = Modifier.clickable { 
                            intentContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/aarushchaudhary/"))) 
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text("Website") },
                        leadingContent = { Icon(Icons.Default.Language, "Website") },
                        modifier = Modifier.clickable { 
                            intentContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aarushchaudhary.vercel.app/"))) 
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                    Divider()
                    ListItem(
                        headlineContent = { Text("GitHub Repository") },
                        leadingContent = { Icon(Icons.Default.Code, "GitHub") },
                        modifier = Modifier.clickable { 
                            intentContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aarushchaudhary/ComLink"))) 
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
    }
}
