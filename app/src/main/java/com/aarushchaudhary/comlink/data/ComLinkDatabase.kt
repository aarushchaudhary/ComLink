package com.aarushchaudhary.comlink.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val publicKeyBase64: String,
    val contactName: String
)

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
    val timestamp: Long
)

@Dao
interface ComLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers")
    fun getAllPeers(): Flow<List<PeerEntity>>

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

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId ORDER BY timestamp ASC")
    fun getMessagesForPeer(deviceId: String): Flow<List<MessageEntity>>
}

@Database(entities = [PeerEntity::class, SessionStateEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class ComLinkDatabase : RoomDatabase() {
    abstract fun comLinkDao(): ComLinkDao
}
