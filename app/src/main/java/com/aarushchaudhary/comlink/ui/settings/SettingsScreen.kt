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

import com.aarushchaudhary.comlink.ui.theme.myDisplayNamePref
import kotlinx.coroutines.flow.map
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import com.aarushchaudhary.comlink.messaging.MeshService

@Composable
fun SettingsScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val accentColor = MaterialTheme.colorScheme.primary
    
    val initialName by context.dataStore.data.map { it[myDisplayNamePref] ?: "Cypherpunk User" }.collectAsState(initial = "Cypherpunk User")
    var localName by remember { mutableStateOf<String?>(null) }
    val displayNameToUse = localName ?: initialName

    // Background delivery switch: on by default. ComLinkViewModel starts/stops MeshService to match.
    val backgroundDelivery by context.dataStore.data.map { it[MeshService.BACKGROUND_DELIVERY_PREF] ?: true }
        .collectAsState(initial = true)
    var confirmTurnOff by remember { mutableStateOf(false) }

    if (confirmTurnOff) {
        AlertDialog(
            onDismissRequest = { confirmTurnOff = false },
            title = { Text("> TURN OFF BACKGROUND DELIVERY? <", fontFamily = FontFamily.Monospace, color = accentColor, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Messages won't be delivered instantly. With this off, ComLink only sends, receives and relays messages while the app is open.",
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmTurnOff = false
                        scope.launch { context.dataStore.edit { it[MeshService.BACKGROUND_DELIVERY_PREF] = false } }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("[ TURN OFF ]", color = Color.Red, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTurnOff = false }) {
                    Text("[ CANCEL ]", color = accentColor, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = Color.Black,
            modifier = Modifier.border(1.dp, accentColor)
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("> SYSTEM OPTIONS <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(32.dp))
        
        Text("MY DISPLAY NAME", color = Color.White, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        
        OutlinedTextField(
            value = displayNameToUse,
            onValueChange = { newName ->
                localName = newName
                scope.launch {
                    context.dataStore.edit { it[myDisplayNamePref] = newName }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = accentColor,
                focusedBorderColor = accentColor,
                unfocusedBorderColor = Color.DarkGray
            ),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
        )

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.DarkGray).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("BACKGROUND DELIVERY", color = Color.White, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (backgroundDelivery) "[ON] Messages arrive instantly, even when ComLink is closed"
                    else "[OFF] Messages only move while ComLink is open",
                    color = if (backgroundDelivery) accentColor else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = backgroundDelivery,
                onCheckedChange = { turnOn ->
                    if (turnOn) {
                        scope.launch { context.dataStore.edit { it[MeshService.BACKGROUND_DELIVERY_PREF] = true } }
                    } else {
                        confirmTurnOff = true // warn first
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(alpha = 0.4f))
            )
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
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aarushchaudhary.tech/")))
                })
                Spacer(Modifier.height(8.dp))
                
                Text("> GITHUB", color = accentColor, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aarushchaudhary/ComLink")))
                })
            }
        }
    }
}
