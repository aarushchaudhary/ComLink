package com.aarushchaudhary.comlink.data

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random

class FileCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto: FileCrypto = run {
        StreamingAeadConfig.register()
        val aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB")).getPrimitive(StreamingAead::class.java)
        FileCrypto(aead)
    }

    private fun data(size: Int) = Random(size).nextBytes(size)
    private fun encFile(name: String = "msg_photo.jpg") = File(tmp.root, crypto.encryptedName(name))

    @Test
    fun roundTripForEverySize() {
        // Empty, tiny, exactly one 4 KB segment, across segments, and a full 10 MB file
        for (size in listOf(0, 1, 4096, 4097, 100_000, 10_000_000)) {
            val file = encFile("f$size")
            crypto.write(file, data(size))
            assertArrayEquals("size $size", data(size), crypto.readBytes(file))
            assertEquals(size.toLong(), crypto.plainSize(file))
        }
    }

    @Test
    fun storedBytesAreNotThePlaintext() {
        val secret = "meet at the bridge at 6".repeat(50).toByteArray()
        val file = encFile()
        crypto.write(file, secret)
        val onDisk = file.readBytes()
        assertTrue(onDisk.size > secret.size) // header + tags
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("meet at the bridge"))
    }

    @Test
    fun randomAccessReadsTheRightBytes() {
        val content = data(350_000)
        val file = encFile()
        crypto.write(file, content)
        // The way file parts are read back for sending: 100 KB slices, last one short
        for ((start, length) in listOf(0L to 100_000, 100_000L to 100_000, 300_000L to 50_000, 4095L to 2)) {
            assertArrayEquals(
                "range $start+$length",
                content.copyOfRange(start.toInt(), start.toInt() + length),
                crypto.readRange(file, start, length)
            )
        }
    }

    @Test
    fun tamperedFileFailsInsteadOfShowingGarbage() {
        val file = encFile()
        crypto.write(file, data(20_000))
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            crypto.readBytes(file)
            fail("a tampered file must not decrypt")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun aFileCantBeSwappedForAnother() {
        // The name is bound into the encryption, so renaming (swapping) a file breaks it
        val original = encFile("a.jpg")
        crypto.write(original, data(1_000))
        val swapped = encFile("b.jpg")
        original.renameTo(swapped)
        try {
            crypto.readBytes(swapped)
            fail("a file moved under another name must not decrypt")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun legacyPlainFilesAreReadAndConverted() {
        val plain = File(tmp.root, "old_photo.jpg").apply { writeBytes(data(5_000)) }
        assertFalse(crypto.isEncrypted(plain))
        assertArrayEquals(data(5_000), crypto.readBytes(plain)) // still readable before conversion

        val converted = crypto.encryptInPlace(plain)
        assertFalse(plain.exists())
        assertEquals("old_photo.jpg.enc", converted.name)
        assertArrayEquals(data(5_000), crypto.readBytes(converted))
        assertEquals(converted, crypto.encryptInPlace(converted)) // already encrypted: unchanged
    }

    @Test
    fun saveToDeviceCopyIsTheOriginalFile() {
        // Save to device streams the decrypted file out (FileTransferManager.exportToDevice)
        for (size in listOf(0, 1, 10_000_000)) {
            val file = encFile("save$size")
            crypto.write(file, data(size))
            val out = ByteArrayOutputStream()
            crypto.openRead(file).use { FileCopy.copy(it, out) }
            assertArrayEquals("size $size", data(size), out.toByteArray())
        }
        // ...and opening in another app uses a decrypted copy
        val source = encFile("doc.pdf")
        crypto.write(source, data(3_000))
        val copy = File(tmp.root, "doc.pdf")
        crypto.decryptTo(source, copy)
        assertArrayEquals(data(3_000), copy.readBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun writingRequiresTheEncSuffix() {
        crypto.write(File(tmp.root, "plain.jpg"), byteArrayOf(1))
    }
}
