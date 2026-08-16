package com.aarushchaudhary.comlink.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.ui.ComLinkViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(contactName, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isOnline) "Online" else if (lastSeen > 0L) "Last seen ${formatTimestamp(lastSeen)}" else "Offline",
                            fontSize = 12.sp,
                            color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f), reverseLayout = true) {
                items(messages.reversed()) { msg ->
                    val alignment = if (msg.isFromMe) Alignment.End else Alignment.Start
                    val color = if (msg.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = alignment
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = color,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { showContextMenuFor = msg }
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (msg.replyToTextSnippet != null) {
                                    Surface(
                                        color = if (msg.isFromMe) MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.8f) else MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth(0.8f)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(msg.replyToSenderId ?: "Unknown", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Text(msg.replyToTextSnippet, fontSize = 12.sp, maxLines = 1)
                                        }
                                    }
                                }
                                
                                Text(msg.plaintext, fontSize = 16.sp)
                                
                                val tickStr = if (msg.isFromMe) {
                                    when (msg.status) {
                                        0 -> " 🕒"
                                        1 -> " ✓"
                                        2 -> " ✓✓"
                                        else -> ""
                                    }
                                } else ""
                                Text(
                                    text = formatTimestamp(msg.timestamp) + tickStr, 
                                    fontSize = 10.sp, 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            if (replyingTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Replying to ${if (replyingTo!!.isFromMe) "You" else contactName}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Text(replyingTo!!.plaintext, maxLines = 1, fontSize = 12.sp)
                        }
                        IconButton(onClick = { replyingTo = null }) {
                            Icon(Icons.Default.Close, "Cancel Reply")
                        }
                    }
                }
            }
            
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
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
                }) {
                    Text("Send")
                }
            }
        }
        
        if (showContextMenuFor != null) {
            ModalBottomSheet(onDismissRequest = { showContextMenuFor = null }) {
                Column(Modifier.padding(bottom = 32.dp)) {
                    ListItem(
                        headlineContent = { Text("Reply") },
                        modifier = Modifier.clickable {
                            replyingTo = showContextMenuFor
                            showContextMenuFor = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Copy Text") },
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(showContextMenuFor!!.plaintext))
                            showContextMenuFor = null
                        }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(time: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(time))
}
