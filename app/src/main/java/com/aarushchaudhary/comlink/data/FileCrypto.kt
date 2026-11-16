package com.aarushchaudhary.comlink.data

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Files at rest are encrypted with Tink streaming AEAD (AES-256-GCM-HKDF, 4 KB segments) and named
 * `<name>.enc`. A file without that suffix is a plain file from before encryption existed; it is
 * still read as-is until [encryptInPlace] converts it.
 *
 * The file's name (without `.enc`) is bound as associated data, so one encrypted file can't be
 * swapped in for another. Pure Java + Tink, no Android imports.
 */
class FileCrypto(private val aead: StreamingAead) {

    companion object {
        const val SUFFIX = ".enc"
    }

    fun isEncrypted(file: File): Boolean = file.name.endsWith(SUFFIX)

    /** The encrypted name for a file that would otherwise be called [plainName]. */
    fun encryptedName(plainName: String): String = plainName + SUFFIX

    private fun associatedData(file: File): ByteArray = file.name.removeSuffix(SUFFIX).toByteArray(Charsets.UTF_8)

    /** Writes [file] encrypted. Its name must end in [SUFFIX]. */
    fun openWrite(file: File): OutputStream {
        require(isEncrypted(file)) { "Encrypted files must end in $SUFFIX: ${file.name}" }
        return aead.newEncryptingStream(FileOutputStream(file), associatedData(file))
    }

    fun write(file: File, bytes: ByteArray) {
        openWrite(file).use { it.write(bytes) }
    }

    /** Plaintext of [file]; a tampered encrypted file fails with an IOException while reading. */
    fun openRead(file: File): InputStream =
        if (isEncrypted(file)) aead.newDecryptingStream(FileInputStream(file), associatedData(file))
        else FileInputStream(file)

    fun readBytes(file: File): ByteArray = openRead(file).use { it.readBytes() }

    /** Random access to the plaintext (positions and size() are plaintext offsets). */
    fun openSeekable(file: File): SeekableByteChannel {
        if (!isEncrypted(file)) return FileInputStream(file).channel
        val channel = aead.newSeekableDecryptingChannel(FileInputStream(file).channel, associatedData(file))
        try {
            // Tink only picks the key (and can only report size()) after a first read. Read one
            // byte, then rewind; a tampered or swapped file fails right here.
            channel.read(ByteBuffer.allocate(1))
            channel.position(0)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
        return channel
    }

    /** Plaintext size (an encrypted file is longer on disk than its content). */
    fun plainSize(file: File): Long {
        if (!isEncrypted(file)) return file.length()
        return try {
            openSeekable(file).use { it.size() }
        } catch (e: java.io.IOException) {
            // An empty plaintext hits end-of-file before Tink picks the key, so size() can't answer;
            // the streaming decrypter confirms it is genuinely (and authentically) empty
            if (openRead(file).use { it.read() == -1 }) 0L else throw e
        }
    }

    /** Reads exactly [length] plaintext bytes starting at [position]. */
    fun readRange(file: File, position: Long, length: Int): ByteArray =
        openSeekable(file).use { channel ->
            channel.position(position)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw java.io.EOFException("Unexpected end of ${file.name}")
            }
            buffer.array()
        }

    /**
     * Converts a plain file into `<name>.enc` next to it, checks the result decrypts to the same
     * size, then deletes the plain file. Returns the encrypted file (or [plain] itself when it is
     * already encrypted).
     */
    fun encryptInPlace(plain: File): File {
        if (isEncrypted(plain)) return plain
        val target = File(plain.parentFile, encryptedName(plain.name))
        val copied = FileInputStream(plain).use { input -> openWrite(target).use { output -> FileCopy.copy(input, output) } }
        if (copied != plain.length() || plainSize(target) != copied) {
            target.delete()
            throw java.io.IOException("Encrypted copy of ${plain.name} didn't verify")
        }
        plain.delete()
        return target
    }

    /** Decrypts [source] into [destination] (a plain file, e.g. a temporary copy to share). */
    fun decryptTo(source: File, destination: File): Long =
        openRead(source).use { input -> FileOutputStream(destination).use { output -> FileCopy.copy(input, output) } }
}
