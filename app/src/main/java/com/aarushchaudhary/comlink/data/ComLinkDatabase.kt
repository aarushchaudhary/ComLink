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
    val nickname: String? = null
) {
    constructor(deviceId: String, publicKeyBase64: String, contactName: String, lastSeenTimestamp: Long) : 
        this(deviceId, publicKeyBase64, contactName, lastSeenTimestamp, false, "", null)
}

@Entity(tableName = "session_states")
data class SessionStateEntity(
    @PrimaryKey val deviceId: String,
    val myNextCounter: Int = 0,
    val peerHighestCounter: Int = -1
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
    @ColumnInfo(defaultValue = "0") val status: Int = 0
)

@Dao
interface ComLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersSync(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    fun getPeerFlow(deviceId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeer(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionState(state: SessionStateEntity)

    @Query("SELECT * FROM session_states WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getSessionState(deviceId: String): SessionStateEntity?

    @Query("UPDATE session_states SET myNextCounter = :counter WHERE deviceId = :deviceId")
    suspend fun updateMyCounter(deviceId: String, counter: Int)

    @Query("UPDATE session_states SET peerHighestCounter = :counter WHERE deviceId = :deviceId")
    suspend fun updatePeerCounter(deviceId: String, counter: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND plaintext NOT LIKE 'ACK:%' ORDER BY timestamp ASC")
    fun getMessagesForPeer(deviceId: String): Flow<List<MessageEntity>>

    @Query("UPDATE peers SET isDirectlyConnected = :isConnected WHERE deviceId = :deviceId")
    suspend fun updatePeerConnectedStatus(deviceId: String, isConnected: Boolean)

    @Query("UPDATE peers SET lastSeenTimestamp = :timestamp WHERE deviceId = :deviceId")
    suspend fun updatePeerLastSeen(deviceId: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND status = 0 AND isFromMe = 1 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(deviceId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: Int)
}

@Database(
    entities = [PeerEntity::class, SessionStateEntity::class, MessageEntity::class], 
    version = 4, 
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)
    ]
)
abstract class ComLinkDatabase : RoomDatabase() {
    abstract fun comLinkDao(): ComLinkDao
}
