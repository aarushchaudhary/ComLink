package com.aarushchaudhary.comlink.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.aarushchaudhary.comlink.ui.CypherpunkColorWheel
import com.aarushchaudhary.comlink.ui.theme.customAccentColorPref
import com.aarushchaudhary.comlink.ui.theme.dataStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("> SYSTEM OPTIONS <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(32.dp))
        
        Text("SET ACCENT COLOR", color = Color.White, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        
        CypherpunkColorWheel(
            onColorSelected = { color ->
                scope.launch {
                    context.dataStore.edit { it[customAccentColorPref] = color.toArgb() }
                }
            },
            modifier = Modifier.size(200.dp)
        )

        Spacer(Modifier.height(32.dp))

        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.DarkGray).padding(16.dp)
        ) {
            Column {
                Text("COMLINK v1.0.0", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                
                Text("> DEV: Aarush Chaudhary", color = Color.White, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                
                Text("> LINKEDIN", color = accentColor, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/aarushchaudhary/")))
                })
                Spacer(Modifier.height(8.dp))
                
                Text("> WEBSITE", color = accentColor, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aarushchaudhary.vercel.app/")))
                })
                Spacer(Modifier.height(8.dp))
                
                Text("> GITHUB", color = accentColor, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aarushchaudhary/ComLink")))
                })
            }
        }
    }
}
