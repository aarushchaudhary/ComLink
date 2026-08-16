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
import androidx.compose.foundation.clickable
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
    object PeerList : Screen()
    object AddContact : Screen()
    object Settings : Screen()
    data class Chat(val deviceId: String, val contactName: String) : Screen()
}

@Composable
fun ComLinkAppUi(viewModel: ComLinkViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.PeerList) }
    val context = LocalContext.current

    when (val screen = currentScreen) {
        is Screen.PeerList -> PeerListScreen(
            viewModel = viewModel,
            onNavigateToAddContact = { currentScreen = Screen.AddContact },
            onNavigateToChat = { devId, name -> currentScreen = Screen.Chat(devId, name) },
            onNavigateToSettings = { currentScreen = Screen.Settings }
        )
        is Screen.AddContact -> AddContactScreen(
            viewModel = viewModel,
            onBack = { currentScreen = Screen.PeerList },
            onContactSaved = { currentScreen = Screen.PeerList }
        )
        is Screen.Settings -> SettingsScreen(
            context = context,
            onBack = { currentScreen = Screen.PeerList }
        )
        is Screen.Chat -> {
            val peer by viewModel.getPeerFlow(screen.deviceId).collectAsState(initial = null)
            ConversationScreen(
                viewModel = viewModel,
                peerId = screen.deviceId,
                contactName = screen.contactName,
                isOnline = peer?.isDirectlyConnected ?: false,
                lastSeen = peer?.lastSeenTimestamp ?: 0L,
                onBack = { currentScreen = Screen.PeerList }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    viewModel: ComLinkViewModel,
    onNavigateToAddContact: () -> Unit,
    onNavigateToChat: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val peers by viewModel.peers.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ComLink", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddContact) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact")
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(peers) { peer ->
                ListItem(
                    headlineContent = { Text(peer.contactName, fontWeight = FontWeight.Bold) },
                    supportingContent = { 
                        Text(
                            text = if (peer.isDirectlyConnected) "Online" else if (peer.lastSeenTimestamp > 0L) "Last seen ${formatTimestampShort(peer.lastSeenTimestamp)}" else "Offline",
                            color = if (peer.isDirectlyConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier.clickable { onNavigateToChat(peer.deviceId, peer.contactName) }
                )
                Divider()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    viewModel: ComLinkViewModel,
    onBack: () -> Unit,
    onContactSaved: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }
    var scannedData by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var contactNameInput by remember { mutableStateOf("") }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
    }

    if (scannedData != null) {
        AlertDialog(
            onDismissRequest = { scannedData = null },
            title = { Text("Verify Fingerprint") },
            text = {
                Column {
                    Text("Manually verify this SHA-256 fingerprint with the peer before accepting:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(fingerprint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                    onContactSaved()
                }) {
                    Text("CONFIRM")
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedData = null }) {
                    Text("CANCEL")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!showScanner) {
                Text("Your QR Identity", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                val qrBitmap = remember { QrUtils.generateQrBitmap(viewModel.getMyQrPayload()) }
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(250.dp))
                
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { 
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScanner = true
                    } else {
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Text("Scan Peer QR")
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
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
                        modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                    ) {
                        Text("Cancel Scan")
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