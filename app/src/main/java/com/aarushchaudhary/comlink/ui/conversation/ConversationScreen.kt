package com.aarushchaudhary.comlink.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.ui.ComLinkViewModel
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
    onBack: () -> Unit
) {
    val messages by viewModel.getMessages(peerId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    
    val clipboardManager = LocalClipboardManager.current
    var showContextMenuFor by remember { mutableStateOf<MessageEntity?>(null) }
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background

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
                val statusText = if (isOnline) "[ONLINE]" else if (lastSeen > 0L) "[OFFLINE] ${formatTimestamp(lastSeen)}" else "[OFFLINE]"
                Text(statusText, color = if (isOnline) accentColor else Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = { viewModel.checkConnection(peerId) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Check Connection", tint = accentColor)
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
                            
                            Text(msg.plaintext, fontSize = 16.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                            
                            val tickStr = if (msg.isFromMe) {
                                when (msg.status) {
                                    0 -> " [WAIT]"
                                    1 -> " [SENT]"
                                    2 -> " [ACK]"
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
        
        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, accentColor).padding(4.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        viewModel.sendMessage(
                            peerId = peerId, 
                            plaintext = inputText,
                            replyToId = replyingTo?.messageId,
                            replyToSender = if (replyingTo?.isFromMe == true) "You" else contactName,
                            replyToSnippet = replyingTo?.plaintext?.take(50)
                        )
                        inputText = ""
                        replyingTo = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text("[SEND]", color = accentColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
    
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
            }
        }
    }
}

private fun formatTimestamp(time: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(time))
}
