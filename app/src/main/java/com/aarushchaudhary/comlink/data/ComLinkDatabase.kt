package com.aarushchaudhary.comlink.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val publicKeyBase64: String,
    val contactName: String,
    val lastSeenTimestamp: Long = 0L,
    @ColumnInfo(defaultValue = "false") var isDirectlyConnected: Boolean = false,
    @ColumnInfo(defaultValue = "") val exchangedName: String = "",
    val nickname: String? = null,
    @ColumnInfo(defaultValue = "false") var isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") var lastMessageTimestamp: Long = 0L
) {
    constructor(deviceId: String, publicKeyBase64: String, contactName: String, lastSeenTimestamp: Long) : 
        this(deviceId, publicKeyBase64, contactName, lastSeenTimestamp, false, "", null, false, 0L)
}

@Entity(tableName = "session_states")
data class SessionStateEntity(
    @PrimaryKey val deviceId: String,
    val myNextCounter: Int = 0,
    val peerHighestCounter: Int = -1,
    // ReplayWindow bits (32 bytes); null for sessions saved before v8
    val peerSeenWindow: ByteArray? = null
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val messageId: String,
    val peerDeviceId: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val partCount: Int,
    @ColumnInfo(defaultValue = "0") val receivedParts: Int = 0,
    val isOutgoing: Boolean,
    @ColumnInfo(defaultValue = "0") val lastSentPartIndex: Int = 0,
    @ColumnInfo(defaultValue = "IN_PROGRESS") val status: String = "IN_PROGRESS",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["deviceId"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deviceId")]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val isFromMe: Boolean,
    val plaintext: String,
    val timestamp: Long,
    val replyToMessageId: String? = null,
    val replyToSenderId: String? = null,
    val replyToTextSnippet: String? = null,
    // File attachment fields
    @ColumnInfo(defaultValue = "TEXT") val messageType: String = "TEXT",  // TEXT, IMAGE, DOCUMENT, AUDIO
    val fileTransferId: String? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    val fileSize: Long? = null,
    val filePath: String? = null,
    val thumbnailPath: String? = null,
    @ColumnInfo(defaultValue = "0") val transferProgress: Float = 0f,
    @ColumnInfo(defaultValue = "0") val status: Int = 0
)

@Dao
interface ComLinkDao {

    // IGNORE, never REPLACE: SQLite's REPLACE deletes the row first, and the messages
    // foreign key (onDelete = CASCADE) would then wipe the whole chat. Change existing
    // contacts with the UPDATE queries below.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET nickname = :nickname WHERE deviceId = :deviceId")
    suspend fun updatePeerNickname(deviceId: String, nickname: String?)

    @Query("UPDATE peers SET publicKeyBase64 = :publicKeyBase64, contactName = :contactName, exchangedName = :exchangedName WHERE deviceId = :deviceId")
    suspend fun updatePeerIdentity(deviceId: String, publicKeyBase64: String, contactName: String, exchangedName: String)

    @Query("SELECT * FROM peers")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersSync(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE isDirectlyConnected = 1")
    suspend fun getConnectedPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    fun getPeerFlow(deviceId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeer(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionState(state: SessionStateEntity)

    @Query("SELECT * FROM session_states WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getSessionState(deviceId: String): SessionStateEntity?

    /** Fresh counters for a contact whose key changed (a new key means a new nonce space). */
    @Query("UPDATE session_states SET myNextCounter = 0, peerHighestCounter = -1, peerSeenWindow = NULL WHERE deviceId = :deviceId")
    suspend fun resetSessionState(deviceId: String)

    @Query("UPDATE session_states SET myNextCounter = :counter WHERE deviceId = :deviceId")
    suspend fun updateMyCounter(deviceId: String, counter: Int)

    @Query("UPDATE session_states SET peerHighestCounter = :highest, peerSeenWindow = :window WHERE deviceId = :deviceId")
    suspend fun updatePeerReplayState(deviceId: String, highest: Int, window: ByteArray)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND plaintext NOT LIKE 'ACK:%' ORDER BY timestamp ASC")
    fun getMessagesForPeer(deviceId: String): Flow<List<MessageEntity>>

    @Query("UPDATE peers SET isDirectlyConnected = :isConnected WHERE deviceId = :deviceId")
    suspend fun updatePeerConnectedStatus(deviceId: String, isConnected: Boolean)

    @Query("UPDATE peers SET lastSeenTimestamp = :timestamp WHERE deviceId = :deviceId")
    suspend fun updatePeerLastSeen(deviceId: String, timestamp: Long)

    @Query("UPDATE peers SET lastMessageTimestamp = :timestamp WHERE deviceId = :deviceId")
    suspend fun updatePeerLastMessageTime(deviceId: String, timestamp: Long)

    @Query("UPDATE peers SET isPinned = :isPinned WHERE deviceId = :deviceId")
    suspend fun updatePeerPinned(deviceId: String, isPinned: Boolean)

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND status = 0 AND isFromMe = 1 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(deviceId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: Int)

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND isFromMe = 0 AND status = 0 ORDER BY timestamp ASC")
    suspend fun getUnreadMessages(deviceId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = 1 WHERE deviceId = :deviceId AND isFromMe = 0 AND status = 0")
    suspend fun markIncomingMessagesAsRead(deviceId: String)

    // File transfer operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileTransfer(transfer: FileTransferEntity)

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getFileTransfer(transferId: String): FileTransferEntity?

    /** For files sent before transfer ID = message ID, when the two IDs still differ. */
    @Query("SELECT * FROM file_transfers WHERE messageId = :messageId AND isOutgoing = 1 LIMIT 1")
    suspend fun getOutgoingTransferByMessageId(messageId: String): FileTransferEntity?

    @Query("UPDATE file_transfers SET receivedParts = receivedParts + 1 WHERE transferId = :transferId")
    suspend fun incrementReceivedParts(transferId: String)

    @Query("UPDATE file_transfers SET status = :status WHERE transferId = :transferId")
    suspend fun updateTransferStatus(transferId: String, status: String)

    @Query("UPDATE file_transfers SET lastSentPartIndex = :partIndex WHERE transferId = :transferId")
    suspend fun updateLastSentPartIndex(transferId: String, partIndex: Int)

    @Query("UPDATE messages SET filePath = :path, transferProgress = 1.0, status = :status WHERE messageId = :messageId")
    suspend fun completeFileMessage(messageId: String, path: String, status: Int)

    /** Sender side: the local copy exists, but progress stays as it is until the parts are out. */
    @Query("UPDATE messages SET filePath = :path WHERE messageId = :messageId")
    suspend fun setLocalFilePath(messageId: String, path: String)

    @Query("UPDATE messages SET transferProgress = :progress WHERE messageId = :messageId")
    suspend fun updateTransferProgress(messageId: String, progress: Float)

    @Query("UPDATE messages SET thumbnailPath = :path WHERE messageId = :messageId")
    suspend fun updateThumbnailPath(messageId: String, path: String)
}

@Database(
    entities = [PeerEntity::class, SessionStateEntity::class, MessageEntity::class, FileTransferEntity::class], 
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
    ]
)
abstract class ComLinkDatabase : RoomDatabase() {
    abstract fun comLinkDao(): ComLinkDao
}
