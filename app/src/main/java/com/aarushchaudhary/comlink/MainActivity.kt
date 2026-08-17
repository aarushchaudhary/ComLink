package com.aarushchaudhary.comlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aarushchaudhary.comlink.ui.ComLinkViewModel
import com.aarushchaudhary.comlink.ui.QrImageAnalyzer
import com.aarushchaudhary.comlink.ui.QrUtils
import com.aarushchaudhary.comlink.ui.conversation.ConversationScreen
import com.aarushchaudhary.comlink.ui.settings.SettingsScreen
import com.aarushchaudhary.comlink.ui.theme.ComLinkTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel: ComLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ComLinkTheme(context = this) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionGate(
                        onPermissionsGranted = {
                            viewModel.startBluetoothIfPermitted()
                            ComLinkAppUi(viewModel)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionGate(onPermissionsGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    
    val bluetoothPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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

    var hasPermissions by remember {
        mutableStateOf(bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    if (hasPermissions) {
        onPermissionsGranted()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Permissions Required", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "ComLink requires Bluetooth permissions to connect to nearby peers. We do not use the internet or central servers.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { launcher.launch(bluetoothPermissions.toTypedArray()) }) {
                Text("Grant Permissions")
            }
        }
    }
}

sealed class Screen {
    data class MainTabs(val tabIndex: Int = 0) : Screen()
    data class Chat(val deviceId: String, val contactName: String) : Screen()
}

@Composable
fun ComLinkAppUi(viewModel: ComLinkViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.MainTabs(0)) }
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background

    when (val screen = currentScreen) {
        is Screen.MainTabs -> {
            Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
                Box(modifier = Modifier.weight(1f)) {
                    when (screen.tabIndex) {
                        0 -> ChatsTab(
                            viewModel = viewModel,
                            onNavigateToChat = { devId, name -> currentScreen = Screen.Chat(devId, name) }
                        )
                        1 -> ContactsTab(
                            viewModel = viewModel,
                            onNavigateToChat = { devId, name -> currentScreen = Screen.Chat(devId, name) }
                        )
                        2 -> SettingsScreen(
                            context = context,
                            onBack = { currentScreen = Screen.MainTabs(0) }
                        )
                    }
                }
                com.aarushchaudhary.comlink.ui.CypherpunkBottomBar(
                    currentTab = screen.tabIndex,
                    onTabSelected = { currentScreen = Screen.MainTabs(it) },
                    accentColor = accentColor
                )
            }
        }
        is Screen.Chat -> {
            BackHandler(onBack = { currentScreen = Screen.MainTabs(0) })
            val peer by viewModel.getPeerFlow(screen.deviceId).collectAsState(initial = null)
            ConversationScreen(
                viewModel = viewModel,
                peerId = screen.deviceId,
                contactName = peer?.nickname ?: peer?.exchangedName?.takeIf { it.isNotBlank() } ?: screen.contactName,
                isOnline = peer?.isDirectlyConnected ?: false,
                lastSeen = peer?.lastSeenTimestamp ?: 0L,
                onBack = { currentScreen = Screen.MainTabs(0) }
            )
        }
    }
}

@Composable
fun ChatsTab(
    viewModel: ComLinkViewModel,
    onNavigateToChat: (String, String) -> Unit
) {
    val peers by viewModel.peers.collectAsState(initial = emptyList())
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    
    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("> RECENT CHATS <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(peers) { peer ->
                val displayName = peer.nickname ?: peer.exchangedName.takeIf { it.isNotBlank() } ?: peer.contactName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToChat(peer.deviceId, displayName) }
                        .border(1.dp, androidx.compose.ui.graphics.Color.DarkGray)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(displayName, color = androidx.compose.ui.graphics.Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        val statusText = if (peer.isDirectlyConnected) "[ONLINE]" else "[OFFLINE] ${formatTimestampShort(peer.lastSeenTimestamp)}"
                        Text(statusText, color = if (peer.isDirectlyConnected) accentColor else androidx.compose.ui.graphics.Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun QrScanner(onQrScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            Executors.newSingleThreadExecutor(),
                            QrImageAnalyzer { result ->
                                onQrScanned(result)
                            }
                        )
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, executor)
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(viewModel: ComLinkViewModel, onNavigateToChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background

    var showScanner by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    
    var scannedData by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var contactNameInput by remember { mutableStateOf("") }
    
    var showEditNicknameFor by remember { mutableStateOf<com.aarushchaudhary.comlink.data.PeerEntity?>(null) }
    var nicknameInput by remember { mutableStateOf("") }
    
    val peers by viewModel.peers.collectAsState(initial = emptyList())

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
    }

    if (scannedData != null) {
        AlertDialog(
            onDismissRequest = { scannedData = null },
            title = { Text("VERIFY FINGERPRINT", fontFamily = FontFamily.Monospace, color = accentColor) },
            text = {
                Column {
                    Text("Manual verification required:", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = androidx.compose.ui.graphics.Color.LightGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(fingerprint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = accentColor)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = contactNameInput,
                        onValueChange = { contactNameInput = it },
                        label = { Text("Contact Name") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.processScannedQr(scannedData!!, contactNameInput.ifBlank { "Unknown" })
                    scannedData = null
                    showScanner = false
                }) {
                    Text("ACCEPT")
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedData = null }) {
                    Text("REJECT")
                }
            },
            containerColor = androidx.compose.ui.graphics.Color.DarkGray
        )
    }

    if (showEditNicknameFor != null) {
        AlertDialog(
            onDismissRequest = { showEditNicknameFor = null },
            title = { Text("EDIT NICKNAME", fontFamily = FontFamily.Monospace, color = accentColor) },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text("Nickname") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateNickname(showEditNicknameFor!!.deviceId, nicknameInput)
                    showEditNicknameFor = null
                }) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNicknameFor = null }) {
                    Text("CANCEL")
                }
            },
            containerColor = androidx.compose.ui.graphics.Color.DarkGray
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(backgroundColor)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("> ADDRESS BOOK <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }

        if (showScanner) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                QrScanner(
                    onQrScanned = { result ->
                        if (scannedData == null) {
                            scannedData = result
                            fingerprint = viewModel.generateFingerprint(result.substringAfter(":"))
                        }
                    }
                )
                Button(
                    onClick = { showScanner = false },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.5f))
                ) {
                    Text("[ CANCEL SCAN ]", fontFamily = FontFamily.Monospace)
                }
            }
        } else if (showMyQr) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("YOUR QR IDENTITY", color = androidx.compose.ui.graphics.Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                
                val qrBitmap = remember { QrUtils.generateQrBitmap(viewModel.getMyQrPayload()) }
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(250.dp).border(2.dp, accentColor))
                
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { showMyQr = false },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.DarkGray)
                ) {
                    Text("[ CLOSE ]", color = accentColor, fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { showMyQr = true },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.DarkGray)
                ) {
                    Text("[ SHOW MY QR ]", color = accentColor, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { 
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            showScanner = true
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.DarkGray)
                ) {
                    Text("[ SCAN QR ]", color = accentColor, fontFamily = FontFamily.Monospace)
                }
            }

            // Contact List
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(peers) { peer ->
                    val displayName = peer.nickname ?: peer.exchangedName.takeIf { it.isNotBlank() } ?: peer.contactName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, androidx.compose.ui.graphics.Color.DarkGray)
                            .combinedClickable(
                                onClick = { onNavigateToChat(peer.deviceId, displayName) },
                                onLongClick = { 
                                    nicknameInput = peer.nickname ?: peer.exchangedName.takeIf { it.isNotBlank() } ?: peer.contactName
                                    showEditNicknameFor = peer 
                                }
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(displayName, color = androidx.compose.ui.graphics.Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(peer.deviceId.take(8) + "...", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestampShort(time: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(time))
}