package com.aarushchaudhary.comlink.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.aarushchaudhary.comlink.ui.CypherpunkColorWheel
import com.aarushchaudhary.comlink.ui.theme.customAccentColorPref
import com.aarushchaudhary.comlink.ui.theme.dataStore
import com.aarushchaudhary.comlink.ui.theme.myDisplayNamePref
import com.aarushchaudhary.comlink.ui.theme.onboardingCompletePref
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    context: Context,
    accentColor: Color,
    onComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            0 -> WelcomeStep(accentColor) { step = 1 }
            1 -> NameStep(accentColor, displayName, onNameChange = { displayName = it }) {
                scope.launch {
                    context.dataStore.edit { it[myDisplayNamePref] = displayName.ifBlank { "Cypherpunk User" } }
                }
                step = 2
            }
            2 -> ColorStep(context, accentColor) { step = 3 }
            3 -> PermissionStep(context, accentColor) {
                scope.launch {
                    context.dataStore.edit { it[onboardingCompletePref] = "true" }
                }
                onComplete()
            }
        }
    }
}

@Composable
private fun WelcomeStep(accentColor: Color, onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "> COMLINK <",
            color = accentColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Encrypted. Off-Grid. Peer-to-Peer.",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A mesh messenger that needs no towers, no servers, no internet. Just Bluetooth and the devices around you.",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            modifier = Modifier.border(1.dp, accentColor)
        ) {
            Text("[ GET STARTED ]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NameStep(
    accentColor: Color,
    name: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("> IDENTITY SETUP <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Choose a display name. Peers will see this when you exchange QR codes.", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text("Enter your name...", color = Color.Gray, fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = accentColor,
                focusedBorderColor = accentColor,
                unfocusedBorderColor = Color.DarkGray
            ),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )
        Spacer(Modifier.height(8.dp))
        Text("You can change this later in Settings.", color = Color.DarkGray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            modifier = Modifier.border(1.dp, accentColor)
        ) {
            Text("[ CONTINUE ]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ColorStep(context: Context, accentColor: Color, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("> ACCENT COLOR <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Pick a color that defines your interface.", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        CypherpunkColorWheel(
            onColorSelected = { color ->
                scope.launch {
                    context.dataStore.edit { it[customAccentColorPref] = color.toArgb() }
                }
            },
            modifier = Modifier.size(220.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            modifier = Modifier.border(1.dp, accentColor)
        ) {
            Text("[ CONTINUE ]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionStep(context: Context, accentColor: Color, onComplete: () -> Unit) {
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val cameraPermission = Manifest.permission.CAMERA
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else null

    var bluetoothGranted by remember {
        mutableStateOf(bluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, cameraPermission) == PackageManager.PERMISSION_GRANTED)
    }
    var notificationGranted by remember {
        mutableStateOf(notificationPermission == null || ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED)
    }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        bluetoothGranted = results.values.all { it }
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("> PERMISSIONS <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("ComLink needs these to function. No data leaves your device.", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        Spacer(Modifier.height(32.dp))

        // Bluetooth
        PermissionRow("BLUETOOTH", "Mesh networking & peer discovery", bluetoothGranted, accentColor) {
            btLauncher.launch(bluetoothPermissions.toTypedArray())
        }
        Spacer(Modifier.height(12.dp))

        // Camera
        PermissionRow("CAMERA", "Scan QR codes for key exchange", cameraGranted, accentColor) {
            camLauncher.launch(cameraPermission)
        }
        Spacer(Modifier.height(12.dp))

        // Notifications
        if (notificationPermission != null) {
            PermissionRow("NOTIFICATIONS", "Alerts for incoming messages", notificationGranted, accentColor) {
                notifLauncher.launch(notificationPermission)
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))

        val allGranted = bluetoothGranted && cameraGranted && notificationGranted
        Button(
            onClick = onComplete,
            enabled = bluetoothGranted,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) Color.DarkGray else Color.DarkGray.copy(alpha = 0.5f),
                disabledContainerColor = Color.DarkGray.copy(alpha = 0.3f)
            ),
            modifier = Modifier.border(1.dp, if (bluetoothGranted) accentColor else Color.DarkGray)
        ) {
            Text(
                if (allGranted) "[ ENTER COMLINK ]" else if (bluetoothGranted) "[ CONTINUE ANYWAY ]" else "[ GRANT BLUETOOTH FIRST ]",
                color = if (bluetoothGranted) accentColor else Color.DarkGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    description: String,
    granted: Boolean,
    accentColor: Color,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (granted) accentColor else Color.DarkGray)
            .background(if (granted) accentColor.copy(alpha = 0.05f) else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        if (granted) {
            Text("[OK]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("[GRANT]", color = accentColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
