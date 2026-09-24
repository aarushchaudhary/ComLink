package com.aarushchaudhary.comlink.ui.conversation

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.ui.ComLinkViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ComLinkViewModel,
    peerId: String,
    contactName: String,
    isOnline: Boolean,
    lastSeen: Long,
    onBack: () -> Unit,
    isSavedMessages: Boolean = false
) {
    val messages by viewModel.getMessages(peerId).collectAsState(initial = emptyList())
    
    LaunchedEffect(messages) {
        if (!isSavedMessages && messages.any { !it.isFromMe && it.status == 0 }) {
            viewModel.markAsRead(peerId)
        }
    }
    
    var inputText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    
    val clipboardManager = LocalClipboardManager.current
    var showContextMenuFor by remember { mutableStateOf<MessageEntity?>(null) }
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background

    // File pickers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendFileMessage(
                peerId = peerId,
                uri = it,
                replyToId = replyingTo?.messageId,
                replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                replyToSnippet = replyingTo?.plaintext?.take(50)
            )
            replyingTo = null
        }
        showAttachmentPicker = false
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendFileMessage(
                peerId = peerId,
                uri = it,
                replyToId = replyingTo?.messageId,
                replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                replyToSnippet = replyingTo?.plaintext?.take(50)
            )
            replyingTo = null
        }
        showAttachmentPicker = false
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendFileMessage(
                peerId = peerId,
                uri = it,
                replyToId = replyingTo?.messageId,
                replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                replyToSnippet = replyingTo?.plaintext?.take(50)
            )
            replyingTo = null
        }
        showAttachmentPicker = false
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendFileMessage(
                peerId = peerId,
                uri = it,
                replyToId = replyingTo?.messageId,
                replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                replyToSnippet = replyingTo?.plaintext?.take(50)
            )
            replyingTo = null
        }
        showAttachmentPicker = false
    }

    val context = LocalContext.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            fetchLocation(context) { lat, lon ->
                viewModel.sendLocationMessage(
                    peerId = peerId,
                    lat = lat,
                    lon = lon,
                    replyToId = replyingTo?.messageId,
                    replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                    replyToSnippet = replyingTo?.plaintext?.take(50)
                )
                replyingTo = null
            }
        }
        showAttachmentPicker = false
    }

    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        // Cypherpunk Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = accentColor) }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(contactName, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                if (isSavedMessages) {
                    Text("[LOCAL STORAGE]", color = accentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                } else {
                    val statusText = if (isOnline) "[ONLINE]" else if (lastSeen > 0L) "[OFFLINE] ${formatTimestamp(lastSeen)}" else "[OFFLINE]"
                    Text(statusText, color = if (isOnline) accentColor else Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (!isSavedMessages) {
                IconButton(onClick = { viewModel.checkConnection(peerId) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check Connection", tint = accentColor)
                }
            }
        }
        
        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
            items(messages.reversed()) { msg ->
                val alignment = if (msg.isFromMe) Alignment.End else Alignment.Start
                val borderColor = if (msg.isFromMe) accentColor else Color.DarkGray
                
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = alignment
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, borderColor)
                            .background(backgroundColor)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showContextMenuFor = msg }
                            )
                            .padding(10.dp)
                    ) {
                        Column {
                            if (msg.replyToTextSnippet != null) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .border(1.dp, Color.Gray)
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text("> ${msg.replyToSenderId ?: "UNKNOWN"}", fontSize = 11.sp, color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Text(msg.replyToTextSnippet, fontSize = 12.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace, maxLines = 1)
                                    }
                                }
                            }
                            
                            // Render based on message type
                            when (msg.messageType) {
                                "IMAGE" -> ImageMessageContent(msg, accentColor, backgroundColor)
                                "AUDIO" -> AudioMessageContent(msg, accentColor)
                                "VIDEO" -> VideoMessageContent(msg, accentColor, backgroundColor)
                                "DOCUMENT" -> DocumentMessageContent(msg, accentColor)
                                "LOCATION" -> LocationMessageContent(msg, accentColor)
                                else -> {
                                    // Default TEXT rendering
                                    Text(msg.plaintext, fontSize = 16.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                }
                            }
                            
                            val tickStr = if (msg.isFromMe) {
                                when (msg.status) {
                                    0 -> " [WAIT]"
                                    1 -> " [SENT]"
                                    2 -> " [DLVD]"
                                    3 -> " [SEEN]"
                                    else -> ""
                                }
                            } else ""
                            Text(
                                text = formatTimestamp(msg.timestamp) + tickStr, 
                                fontSize = 10.sp, 
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        
        if (replyingTo != null) {
            Box(modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).background(backgroundColor).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REPLYING TO: ${if (replyingTo!!.isFromMe) "YOU" else contactName}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accentColor, fontFamily = FontFamily.Monospace)
                        Text(replyingTo!!.plaintext, maxLines = 1, fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(Icons.Default.Close, "Cancel Reply", tint = Color.Red)
                    }
                }
            }
        }
        
        // Input bar with attachment button
        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(4.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment button (not for saved messages)
            if (!isSavedMessages) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, Color.DarkGray)
                        .clickable { showAttachmentPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("[+]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(4.dp))
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("INPUT COMMAND...", color = Color.Gray, fontFamily = FontFamily.Monospace) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = accentColor,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        if (isSavedMessages) {
                            viewModel.sendSavedNote(inputText)
                        } else {
                            viewModel.sendMessage(
                                peerId = peerId, 
                                plaintext = inputText,
                                replyToId = replyingTo?.messageId,
                                replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                                replyToSnippet = replyingTo?.plaintext?.take(50)
                            )
                        }
                        inputText = ""
                        replyingTo = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text(if (isSavedMessages) "[SAVE]" else "[SEND]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
    
    // Context menu bottom sheet
    if (showContextMenuFor != null) {
        ModalBottomSheet(onDismissRequest = { showContextMenuFor = null }, containerColor = backgroundColor) {
            Column(Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("> REPLY", color = accentColor, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.clickable {
                        replyingTo = showContextMenuFor
                        showContextMenuFor = null
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                ListItem(
                    headlineContent = { Text("> COPY TEXT", color = accentColor, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(showContextMenuFor!!.plaintext))
                        showContextMenuFor = null
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                // Show RETRY only for our own messages still stuck in [WAIT]
                if (!isSavedMessages && showContextMenuFor!!.isFromMe && showContextMenuFor!!.status == 0) {
                    ListItem(
                        headlineContent = { Text("> RETRY SEND", color = Color(0xFFFF9800), fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.clickable {
                            viewModel.retryMessage(peerId, showContextMenuFor!!)
                            showContextMenuFor = null
                        },
                        colors = ListItemDefaults.colors(containerColor = backgroundColor)
                    )
                }
                if (!isSavedMessages) {
                    ListItem(
                        headlineContent = { Text("> SAVE MESSAGE", color = accentColor, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.clickable {
                            val senderName = if (showContextMenuFor!!.isFromMe) "You" else contactName
                            viewModel.saveMessageToSaved(showContextMenuFor!!, senderName)
                            showContextMenuFor = null
                        },
                        colors = ListItemDefaults.colors(containerColor = backgroundColor)
                    )
                }
            }
        }
    }

    // Attachment picker bottom sheet
    if (showAttachmentPicker) {
        ModalBottomSheet(onDismissRequest = { showAttachmentPicker = false }, containerColor = backgroundColor) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("> ATTACH FILE <", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                ListItem(
                    headlineContent = { Text("> IMAGE", color = accentColor, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text("Send a photo", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable {
                        imagePickerLauncher.launch("image/*")
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                ListItem(
                    headlineContent = { Text("> DOCUMENT", color = accentColor, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text("Send a file (PDF, TXT, etc.)", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable {
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                ListItem(
                    headlineContent = { Text("> AUDIO", color = accentColor, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text("Send an audio file", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable {
                        audioPickerLauncher.launch("audio/*")
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                ListItem(
                    headlineContent = { Text("> VIDEO", color = accentColor, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text("Send a video file", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable {
                        videoPickerLauncher.launch("video/*")
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
                ListItem(
                    headlineContent = { Text("> LOCATION", color = accentColor, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text("Share current coordinates", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    modifier = Modifier.clickable {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = backgroundColor)
                )
            }
        }
    }
}

// ─── Media Message Composables ─────────────────────────────────────────

@Composable
private fun ImageMessageContent(msg: MessageEntity, accentColor: Color, backgroundColor: Color) {
    val filePath = msg.filePath
    val thumbnailPath = msg.thumbnailPath

    if (filePath != null && File(filePath).exists()) {
        // Transfer complete — show full image
        val bitmap = remember(filePath) {
            BitmapFactory.decodeFile(filePath)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = msg.fileName ?: "Image",
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(2.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            FileErrorPlaceholder(msg, accentColor)
        }
    } else if (thumbnailPath != null && File(thumbnailPath).exists()) {
        // In progress — show thumbnail + progress
        Box {
            val thumbBitmap = remember(thumbnailPath) {
                BitmapFactory.decodeFile(thumbnailPath)
            }
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = "Thumbnail",
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    contentScale = ContentScale.Fit,
                    alpha = 0.4f
                )
            }
            TransferProgressIndicator(msg, accentColor)
        }
    } else {
        // Waiting — show placeholder with progress
        FileProgressPlaceholder(msg, accentColor)
    }
}

@Composable
private fun AudioMessageContent(msg: MessageEntity, accentColor: Color) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    val filePath = msg.filePath

    if (filePath != null && File(filePath).exists()) {
        Row(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(1.dp, accentColor)
                    .clickable {
                        if (isPlaying) {
                            isPlaying = false
                        } else {
                            isPlaying = true
                            try {
                                val player = MediaPlayer()
                                player.setDataSource(filePath)
                                player.prepare()
                                player.setOnCompletionListener {
                                    isPlaying = false
                                    it.release()
                                }
                                player.start()
                            } catch (e: Exception) {
                                isPlaying = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isPlaying) "||" else ">>",
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    msg.fileName ?: "AUDIO",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    formatFileSize(msg.fileSize ?: 0),
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    } else {
        FileProgressPlaceholder(msg, accentColor)
    }
}

@Composable
private fun DocumentMessageContent(msg: MessageEntity, accentColor: Color) {
    val context = LocalContext.current
    val filePath = msg.filePath
    val fileExists = filePath != null && File(filePath).exists()

    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .then(
                if (fileExists) Modifier.clickable {
                    try {
                        val file = File(filePath!!)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, msg.fileMimeType ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // No app to handle this file type
                    }
                } else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("[DOC]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                msg.fileName ?: "Document",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1
            )
            Text(
                formatFileSize(msg.fileSize ?: 0),
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
    if (!fileExists && msg.transferProgress < 1f) {
        TransferProgressIndicator(msg, accentColor)
    }
}

// ─── Shared Components ─────────────────────────────────────────────────

@Composable
private fun FileProgressPlaceholder(msg: MessageEntity, accentColor: Color) {
    Column(modifier = Modifier.widthIn(max = 240.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[${msg.messageType}]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                msg.fileName ?: "File",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
        if (msg.fileSize != null) {
            Text(
                formatFileSize(msg.fileSize),
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        TransferProgressIndicator(msg, accentColor)
    }
}

@Composable
private fun FileErrorPlaceholder(msg: MessageEntity, accentColor: Color) {
    Text(
        "[FILE ERROR] ${msg.fileName ?: "Unknown"}",
        color = Color.Red,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp
    )
}

@Composable
private fun TransferProgressIndicator(msg: MessageEntity, accentColor: Color) {
    if (msg.transferProgress < 1f) {
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0xFF333333))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(msg.transferProgress.coerceIn(0f, 1f))
                    .background(accentColor)
            )
        }
        Text(
            "${(msg.transferProgress * 100).toInt()}%",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

// ─── Utilities ─────────────────────────────────────────────────────────

private fun formatTimestamp(time: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(time))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@Composable
private fun VideoMessageContent(msg: MessageEntity, accentColor: Color, backgroundColor: Color) {
    val context = LocalContext.current
    val filePath = msg.filePath
    val fileExists = filePath != null && File(filePath).exists()

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(
                if (fileExists) Modifier.clickable {
                    try {
                        val file = File(filePath!!)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, msg.fileMimeType ?: "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                } else Modifier
            )
            .background(Color.DarkGray)
    ) {
        if (msg.thumbnailPath != null && File(msg.thumbnailPath).exists()) {
            val thumbBitmap = remember(msg.thumbnailPath) {
                BitmapFactory.decodeFile(msg.thumbnailPath)
            }
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = if (fileExists) 1f else 0.4f
                )
            }
        }
        
        // Play icon overlay
        if (fileExists) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .border(2.dp, accentColor, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("|>", color = accentColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        
        if (!fileExists) {
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                FileProgressPlaceholder(msg, accentColor)
            }
        }
    }
}

@Composable
private fun LocationMessageContent(msg: MessageEntity, accentColor: Color) {
    val context = LocalContext.current
    val coords = msg.plaintext.split(",")
    val displayCoords = if (coords.size == 2) {
        "Lat: ${coords[0].take(8)}\nLon: ${coords[1].take(8)}"
    } else {
        msg.plaintext
    }

    Row(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clickable {
                if (coords.size == 2) {
                    try {
                        val uri = Uri.parse("geo:${coords[0]},${coords[1]}?q=${coords[0]},${coords[1]}")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.DarkGray, RoundedCornerShape(4.dp))
                .border(1.dp, accentColor, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("[MAP]", color = accentColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("CURRENT LOCATION", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(displayCoords, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

private fun fetchLocation(context: android.content.Context, onLocation: (lat: Double, lon: Double) -> Unit) {
    try {
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            android.location.LocationManager.GPS_PROVIDER
        } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            android.location.LocationManager.NETWORK_PROVIDER
        } else {
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                onLocation(location.latitude, location.longitude)
            } else {
                locationManager.requestSingleUpdate(provider, object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        onLocation(loc.latitude, loc.longitude)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, android.os.Looper.getMainLooper())
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
