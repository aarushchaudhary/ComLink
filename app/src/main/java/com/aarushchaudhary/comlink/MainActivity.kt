package com.aarushchaudhary.comlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aarushchaudhary.comlink.ui.ComLinkViewModel
import com.aarushchaudhary.comlink.ui.QrUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ComLinkViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request Bluetooth & Camera permissions required for mesh operations
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions.launch(permissions.toTypedArray())
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme() // Minimalist dark cypherpunk vibe
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ComLinkAppUi(viewModel)
                }
            }
        }
    }
}

sealed class Screen {
    object PeerList : Screen()
    object AddContact : Screen()
    data class Chat(val deviceId: String, val contactName: String) : Screen()
}

@Composable
fun ComLinkAppUi(viewModel: ComLinkViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.PeerList) }

    when (val screen = currentScreen) {
        is Screen.PeerList -> PeerListScreen(
            viewModel = viewModel,
            onNavigateToAddContact = { currentScreen = Screen.AddContact },
            onNavigateToChat = { devId, name -> currentScreen = Screen.Chat(devId, name) }
        )
        is Screen.AddContact -> AddContactScreen(
            viewModel = viewModel,
            onBack = { currentScreen = Screen.PeerList },
            onContactSaved = { currentScreen = Screen.PeerList }
        )
        is Screen.Chat -> ChatScreen(
            viewModel = viewModel,
            peerId = screen.deviceId,
            contactName = screen.contactName,
            onBack = { currentScreen = Screen.PeerList }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    viewModel: ComLinkViewModel,
    onNavigateToAddContact: () -> Unit,
    onNavigateToChat: (String, String) -> Unit
) {
    val peers by viewModel.peers.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ComLink", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) })
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
                    supportingContent = { Text(peer.deviceId, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable { onNavigateToChat(peer.deviceId, peer.contactName) }
                )
                Divider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    viewModel: ComLinkViewModel,
    onBack: () -> Unit,
    onContactSaved: () -> Unit
) {
    var showScanner by remember { mutableStateOf(false) }
    var scannedData by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var contactNameInput by remember { mutableStateOf("") }

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
                Button(onClick = { showScanner = true }) {
                    Text("Scan Peer QR")
                }
            } else {
                Text("QR Scanner not fully implemented in UI stub.", modifier = Modifier.padding(16.dp))
                Text("Assume successful scan triggers AlertDialog.", modifier = Modifier.padding(16.dp))
                
                Button(onClick = {
                    // Simulating a successful scan of a dummy QR for demo
                    val fakePeerKey = android.util.Base64.encodeToString(ByteArray(32) { 0x01 }, android.util.Base64.NO_WRAP)
                    scannedData = "dummy_device:$fakePeerKey"
                    fingerprint = viewModel.generateFingerprint(fakePeerKey)
                }) {
                    Text("Simulate Scan")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ComLinkViewModel,
    peerId: String,
    contactName: String,
    onBack: () -> Unit
) {
    val messages by viewModel.getMessages(peerId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                // Reverse list so newest is at the bottom (handled partially by reverseLayout)
                items(messages.reversed()) { msg ->
                    val alignment = if (msg.isFromMe) Alignment.End else Alignment.Start
                    val color = if (msg.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = alignment
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = color
                        ) {
                            Text(msg.plaintext, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
            
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(peerId, inputText)
                        inputText = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}