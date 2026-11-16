package com.aarushchaudhary.comlink.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a phone or emulator (SQLCipher is native code). In Android Studio: right-click this file,
 * Run 'SecureDatabaseMigrationTest'. Uses its own database files, never the app's real ones.
 */
@RunWith(AndroidJUnit4::class)
class SecureDatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = "0123456789abcdef".repeat(4) // 64 hex characters, like the real one

    // Own file names: this test runs inside the app's process, and the app opens its real
    // database (comlink_db / comlink_secure_db) at the same time, with its own key
    private val plainName = "test_comlink_db"
    private val encryptedName = "test_comlink_secure_db"

    private fun open() = SecureDatabase.open(context, passphrase, plainName, encryptedName)

    private fun dbFiles() = listOf(plainName, encryptedName, "$encryptedName.migrating")
        .flatMap { name -> listOf("", "-wal", "-shm", "-journal").map { File(context.getDatabasePath(name).path + it) } }

    @Before
    @After
    fun cleanUp() {
        dbFiles().forEach { it.delete() }
    }

    @Test
    fun plainDatabaseIsMovedWithEveryRowAndThenDeleted() = runBlocking {
        // An install from before encryption: a plain Room database with some chats
        val plain = Room.databaseBuilder(context, ComLinkDatabase::class.java, plainName).build()
        val dao = plain.comLinkDao()
        dao.insertPeer(PeerEntity("bob-device-id-000", "a2V5", "Bob", 0L))
        dao.insertSessionState(SessionStateEntity("bob-device-id-000", myNextCounter = 42, peerHighestCounter = 17))
        repeat(250) { i ->
            dao.insertMessage(MessageEntity("m$i", "bob-device-id-000", i % 2 == 0, "hello $i", 1_000L + i))
        }
        plain.close()

        val secure = open()
        val sdao = secure.comLinkDao()
        assertEquals(250, sdao.getMessagesForPeer("bob-device-id-000").first().size)
        assertEquals("Bob", sdao.getPeer("bob-device-id-000")?.contactName)
        assertEquals(42, sdao.getSessionState("bob-device-id-000")?.myNextCounter)
        assertEquals(17, sdao.getSessionState("bob-device-id-000")?.peerHighestCounter)
        secure.close()

        // The plain file is gone, and the new one isn't readable as plain SQLite
        assertFalse(context.getDatabasePath(plainName).exists())
        val encrypted = context.getDatabasePath(encryptedName)
        assertTrue(encrypted.exists())
        val header = encrypted.inputStream().use { input -> ByteArray(16).also { input.read(it) } }
        assertFalse(String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
    }

    @Test
    fun freshInstallGetsAnEncryptedDatabase() = runBlocking {
        val db = open()
        db.comLinkDao().insertPeer(PeerEntity("carol-device-id-0", "a2V5", "Carol", 0L))
        assertNotNull(db.comLinkDao().getPeer("carol-device-id-0"))
        db.close()
        assertFalse(context.getDatabasePath(plainName).exists())
        assertTrue(context.getDatabasePath(encryptedName).exists())
    }

    @Test
    fun reopeningWithTheSameKeyKeepsData() = runBlocking {
        open().apply {
            comLinkDao().insertPeer(PeerEntity("dave-device-id-00", "a2V5", "Dave", 0L))
            close()
        }
        val again = open()
        assertEquals("Dave", again.comLinkDao().getPeer("dave-device-id-00")?.contactName)
        again.close()
    }
}
