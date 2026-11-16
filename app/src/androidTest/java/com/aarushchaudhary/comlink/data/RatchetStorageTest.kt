package com.aarushchaudhary.comlink.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The Double Ratchet's tables and queries, on a real (in-memory) SQLite. */
@RunWith(AndroidJUnit4::class)
class RatchetStorageTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, ComLinkDatabase::class.java).build()
    private val dao = db.comLinkDao()

    @After
    fun close() = db.close()

    private fun key(n: Int, createdAt: Long, peer: String = "bob") =
        SkippedKeyEntity(peer, "abcd", n, byteArrayOf(n.toByte()), createdAt)

    @Test
    fun stateIsSavedReplacedAndDeleted() = runBlocking {
        assertNull(dao.getRatchetState("bob"))
        dao.saveRatchetState(RatchetStateEntity("bob", byteArrayOf(1, 2)))
        dao.saveRatchetState(RatchetStateEntity("bob", byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(3), dao.getRatchetState("bob"))
        dao.deleteRatchetState("bob")
        assertNull(dao.getRatchetState("bob"))
    }

    @Test
    fun skippedKeysAreFoundUsedTrimmedAndExpired() = runBlocking {
        dao.insertSkippedKeys((0 until 10).map { key(it, createdAt = 1_000L + it) } + key(0, 1_000L, peer = "carol"))
        assertArrayEquals(byteArrayOf(4), dao.getSkippedKey("bob", "abcd", 4))
        dao.deleteSkippedKey("bob", "abcd", 4)
        assertNull(dao.getSkippedKey("bob", "abcd", 4))

        // Only the newest 3 of bob's stay; carol's are untouched
        dao.trimSkippedKeys("bob", 3)
        assertEquals(listOf(7, 8, 9).map { byteArrayOf(it.toByte()).toList() },
            (0 until 10).mapNotNull { dao.getSkippedKey("bob", "abcd", it)?.toList() })
        assertArrayEquals(byteArrayOf(0), dao.getSkippedKey("carol", "abcd", 0))

        dao.deleteSkippedKeysOlderThan(1_009L) // expires bob's 7 and 8 and carol's
        assertNull(dao.getSkippedKey("bob", "abcd", 8))
        assertArrayEquals(byteArrayOf(9), dao.getSkippedKey("bob", "abcd", 9))
        assertNull(dao.getSkippedKey("carol", "abcd", 0))

        dao.deleteSkippedKeys("bob")
        assertNull(dao.getSkippedKey("bob", "abcd", 9))
    }
}
