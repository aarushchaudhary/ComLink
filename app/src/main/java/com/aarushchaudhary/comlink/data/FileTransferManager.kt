package com.aarushchaudhary.comlink.data

import java.io.InputStream
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.aarushchaudhary.comlink.messaging.EncryptedSender
import com.aarushchaudhary.comlink.proto.FileHeader
import com.aarushchaudhary.comlink.proto.FilePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Manages multi-envelope file transfers over the BLE mesh.
 *
 * Files are split into ≤100 KB parts. Each part is encrypted and sent as
 * a separate Envelope with content_type = FILE_PART (2).
 * A FILE_HEADER (content_type = 1) is sent first with metadata.
 *
 * Receiving side buffers parts to disk and reassembles when all arrive.
 */
class FileTransferManager(
    private val context: Context,
    private val dao: ComLinkDao,
    private val sender: EncryptedSender
) {

    companion object {
        private const val TAG = "FileTransfer"
        const val PART_SIZE = TransferChecks.PART_SIZE  // ~100 KB per part (stays within 128 KB BLE envelope limit)
        const val MAX_FILE_SIZE = TransferChecks.MAX_FILE_SIZE  // 10 MB hard limit
        const val CONTENT_TYPE_MESSAGE = 0
        const val CONTENT_TYPE_FILE_HEADER = 1
        const val CONTENT_TYPE_FILE_PART = 2
        const val THUMBNAIL_MAX_DIM = 128
        const val THUMBNAIL_QUALITY = 50
        private const val PROGRESS_UPDATE_INTERVAL = 5 // Update progress every N parts
    }

    /** Outcome of [sendFile], so the caller can tell the user why a file didn't go out. */
    enum class SendFileResult { SENT, TOO_LARGE, UNREADABLE, NOT_SENT }

    private sealed class ReadResult {
        class Ok(val bytes: ByteArray) : ReadResult()
        object TooLarge : ReadResult()
        object Unreadable : ReadResult()
    }

    /**
     * Send a file to a peer. The message and the transfer share one ID ([messageId]), the file is
     * copied locally first, then a header and the parts go out (see [transmit]).
     */
    suspend fun sendFile(
        peerId: String,
        uri: Uri,
        messageId: String
    ): SendFileResult {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(uri) ?: "file"
        val transferId = messageId

        val fileBytes = when (val read = readForSending(uri, mimeType)) {
            is ReadResult.Ok -> read.bytes
            ReadResult.TooLarge -> {
                Log.e(TAG, "File too large to send: $uri (max $MAX_FILE_SIZE bytes)")
                recordFailedTransfer(transferId, peerId, fileName, mimeType)
                return SendFileResult.TOO_LARGE
            }
            ReadResult.Unreadable -> {
                Log.e(TAG, "Cannot read file: $uri")
                recordFailedTransfer(transferId, peerId, fileName, mimeType)
                return SendFileResult.UNREADABLE
            }
        }

        val partCount = TransferChecks.expectedPartCount(fileBytes.size.toLong())

        // Generate thumbnail for images
        val thumbnailBytes = if (mimeType.startsWith("image/")) {
            generateThumbnail(fileBytes)
        } else null

        // Save sender-side transfer record
        val transfer = FileTransferEntity(
            transferId = transferId,
            messageId = messageId,
            peerDeviceId = peerId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = fileBytes.size.toLong(),
            partCount = partCount,
            isOutgoing = true
        )
        dao.insertFileTransfer(transfer)

        // Save the file locally so the sender can view it (and so jumpstart can re-read it).
        // Progress stays below 1 until the parts are out, so a failed send can still be retried.
        val localFile = saveFileLocally(transferId, fileName, fileBytes)
        dao.setLocalFilePath(messageId, localFile.absolutePath)

        if (thumbnailBytes != null) {
            val thumbPath = saveThumbnailLocally(transferId, thumbnailBytes)
            dao.updateThumbnailPath(messageId, thumbPath)
        }

        return if (transmit(transfer, localFile.absolutePath, startPart = 0, thumbnailBytes = thumbnailBytes)) {
            SendFileResult.SENT
        } else {
            SendFileResult.NOT_SENT
        }
    }

    /**
     * Jumpstarts a stuck outgoing file: re-sends the header (the receiver ignores a repeat, so parts
     * it already has still count), then the parts from where sending stopped. If every part was
     * already sent, all of them go again; the receiver skips the ones it holds.
     */
    suspend fun resumeOutgoingTransfer(peerId: String, message: MessageEntity) {
        val filePath = message.filePath ?: return
        val file = File(filePath)
        if (!file.exists()) return

        // New transfers use the message ID; files sent before that fix are found by message ID
        val transfer = message.fileTransferId?.let { dao.getFileTransfer(it) }?.takeIf { it.isOutgoing }
            ?: dao.getOutgoingTransferByMessageId(message.messageId)
            ?: FileTransferEntity(
                // No record at all: rebuild one from the message and the local copy
                transferId = message.fileTransferId ?: message.messageId,
                messageId = message.messageId,
                peerDeviceId = peerId,
                fileName = message.fileName ?: file.name,
                mimeType = message.fileMimeType ?: "application/octet-stream",
                fileSize = file.length(),
                partCount = TransferChecks.expectedPartCount(file.length()),
                isOutgoing = true
            ).also { dao.insertFileTransfer(it) }

        val startPart = if (transfer.lastSentPartIndex in 1 until transfer.partCount) transfer.lastSentPartIndex else 0
        val thumbnailBytes = message.thumbnailPath?.let { File(it) }
            ?.takeIf { it.exists() && it.length() <= TransferChecks.MAX_THUMBNAIL_BYTES }
            ?.readBytes()

        Log.i(TAG, "Jumpstarting transfer ${transfer.transferId} from part $startPart of ${transfer.partCount}")
        transmit(transfer, filePath, startPart, thumbnailBytes)
    }

    /**
     * Sends the header, then parts [startPart] until the end, read from the local copy at [filePath].
     * Used by both a fresh send and a jumpstart. Returns true when every part went out.
     */
    private suspend fun transmit(
        transfer: FileTransferEntity,
        filePath: String,
        startPart: Int,
        thumbnailBytes: ByteArray?
    ): Boolean {
        val transferId = transfer.transferId
        val peerId = transfer.peerDeviceId
        val partCount = transfer.partCount
        dao.updateTransferStatus(transferId, "IN_PROGRESS")

        val header = FileHeader(
            transfer_id = transferId,
            file_name = transfer.fileName,
            mime_type = transfer.mimeType,
            file_size = transfer.fileSize,
            part_count = partCount,
            thumbnail = thumbnailBytes?.let { okio.ByteString.of(*it) } ?: okio.ByteString.EMPTY,
            message_id = transfer.messageId
        )
        if (!sender.send(peerId, header.encode(), contentType = CONTENT_TYPE_FILE_HEADER)) {
            Log.e(TAG, "Failed to send FILE_HEADER for transfer $transferId")
            dao.updateTransferStatus(transferId, "FAILED")
            return false
        }

        // Reserve counters upfront for all parts to avoid per-part lock acquisition
        val remainingParts = partCount - startPart
        val counters = sender.reserveCounters(peerId, remainingParts) ?: run {
            Log.e(TAG, "Failed to reserve counters for transfer $transferId")
            dao.updateTransferStatus(transferId, "FAILED")
            return false
        }

        val raf = try { RandomAccessFile(filePath, "r") } catch (e: Exception) {
            Log.e(TAG, "Cannot open local copy for transfer $transferId: $filePath", e)
            dao.updateTransferStatus(transferId, "FAILED")
            return false
        }

        raf.use {
            // Send FILE_PART envelopes sequentially — direct + no-response for speed
            for (i in startPart until partCount) {
                val start = i.toLong() * PART_SIZE
                val end = minOf(start + PART_SIZE, raf.length())
                val partData = ByteArray((end - start).toInt())
                raf.seek(start)
                raf.readFully(partData)

                val part = FilePart(
                    transfer_id = transferId,
                    part_index = i,
                    data_ = okio.ByteString.of(*partData)
                )

                val counterIdx = i - startPart
                if (!sender.sendReserved(peerId, part.encode(), counters[counterIdx], CONTENT_TYPE_FILE_PART)) {
                    Log.e(TAG, "Failed to send FILE_PART $i/$partCount for transfer $transferId")
                    dao.updateTransferStatus(transferId, "FAILED")
                    dao.updateLastSentPartIndex(transferId, i)
                    return false
                }

                // Track last sent part index
                dao.updateLastSentPartIndex(transferId, i + 1)

                // Update progress in the message (batched — every N parts or last part)
                if (counterIdx % PROGRESS_UPDATE_INTERVAL == 0 || i == partCount - 1) {
                    dao.updateTransferProgress(transfer.messageId, (i + 1).toFloat() / partCount)
                }
            }
        }

        dao.updateTransferStatus(transferId, "COMPLETE")
        dao.updateMessageStatus(transfer.messageId, 1) // SENT
        return true
    }

    /** A transfer that never started (too large or unreadable), recorded so it shows as failed. */
    private suspend fun recordFailedTransfer(transferId: String, peerId: String, fileName: String, mimeType: String) {
        dao.insertFileTransfer(FileTransferEntity(
            transferId = transferId,
            messageId = transferId,
            peerDeviceId = peerId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = 0,
            partCount = 0,
            isOutgoing = true,
            status = "FAILED"
        ))
    }

    /**
     * Reads the bytes to send without ever loading more than [MAX_FILE_SIZE] + 1 bytes.
     * Images are shrunk straight from the stream; anything that can't be decoded as an image is
     * sent as a plain file, as before.
     */
    private fun readForSending(uri: Uri, mimeType: String): ReadResult {
        if (mimeType.startsWith("image/")) {
            compressImage(uri)?.let { jpeg ->
                return if (jpeg.size > MAX_FILE_SIZE) ReadResult.TooLarge else ReadResult.Ok(jpeg)
            }
        }
        val declaredSize = getFileSize(uri)
        if (declaredSize != null && declaredSize > MAX_FILE_SIZE) return ReadResult.TooLarge

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_FILE_SIZE + 1) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $uri", e)
            null
        } ?: return ReadResult.Unreadable
        return if (bytes.size > MAX_FILE_SIZE) ReadResult.TooLarge else ReadResult.Ok(bytes)
    }

    /** Reads at most [limit] bytes, so an unknown-size stream can't fill memory. */
    private fun readAtMost(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /**
     * Handle an incoming FILE_HEADER. Creates a placeholder message and
     * prepares the temp directory for receiving parts.
     */
    suspend fun onFileHeaderReceived(senderId: String, decryptedPayload: ByteArray) {
        val header = try {
            FileHeader.ADAPTER.decode(decryptedPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode FileHeader", e)
            return
        }

        val transferId = header.transfer_id
        val messageId = header.message_id.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        // These IDs become file paths and database keys: only well-formed headers are accepted
        if (!TransferChecks.isValidHeader(transferId, messageId, header.file_size, header.part_count)) {
            Log.w(TAG, "Rejected FILE_HEADER from $senderId: invalid ID, size or part count")
            return
        }

        val existingTransfer = dao.getFileTransfer(transferId)
        if (existingTransfer != null) {
            // A repeated header for a transfer we already have (e.g. a jumpstart) changes nothing,
            // so parts already received still count. Someone else's transfer ID is refused.
            if (existingTransfer.isOutgoing || existingTransfer.peerDeviceId != senderId) {
                Log.w(TAG, "Rejected FILE_HEADER from $senderId: transfer ID belongs to another transfer")
            } else {
                getTransferTempDir(transferId).mkdirs()
            }
            return
        }
        val existingMessage = dao.getMessage(messageId)
        if (existingMessage != null) {
            // The message ID is already used (another chat, or one of our own messages): never touch it
            Log.w(TAG, "Rejected FILE_HEADER from $senderId: message ID already in use")
            return
        }

        val fileName = header.file_name.takeIf { it.isNotEmpty() } ?: "file"
        val mimeType = header.mime_type.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

        // Determine message type from MIME
        val messageType = when {
            mimeType.startsWith("image/") -> "IMAGE"
            mimeType.startsWith("audio/") -> "AUDIO"
            mimeType.startsWith("video/") -> "VIDEO"
            else -> "DOCUMENT"
        }

        // Create transfer record
        dao.insertFileTransfer(FileTransferEntity(
            transferId = transferId,
            messageId = messageId,
            peerDeviceId = senderId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = header.file_size,
            partCount = header.part_count,
            isOutgoing = false
        ))

        // Create placeholder message in chat
        val message = MessageEntity(
            messageId = messageId,
            deviceId = senderId,
            isFromMe = false,
            plaintext = "\u2709 $fileName",  // ✉ emoji
            timestamp = System.currentTimeMillis(),
            messageType = messageType,
            fileTransferId = transferId,
            fileName = fileName,
            fileMimeType = mimeType,
            fileSize = header.file_size
        )
        dao.insertMessage(message)
        dao.updatePeerLastMessageTime(senderId, message.timestamp)

        // Save thumbnail if present
        val thumbBytes = header.thumbnail.toByteArray()
        // An oversized thumbnail is skipped; the file itself still comes through
        if (thumbBytes.isNotEmpty() && thumbBytes.size <= TransferChecks.MAX_THUMBNAIL_BYTES) {
            val thumbPath = saveThumbnailLocally(transferId, thumbBytes)
            dao.updateThumbnailPath(messageId, thumbPath)
        }

        // Create temp directory for parts
        getTransferTempDir(transferId).mkdirs()
    }

    /**
     * Handle an incoming FILE_PART. Buffers the part to disk and
     * assembles the file when all parts have arrived.
     */
    suspend fun onFilePartReceived(senderId: String, decryptedPayload: ByteArray) {
        val part = try {
            FilePart.ADAPTER.decode(decryptedPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode FilePart", e)
            return
        }

        val transferId = part.transfer_id
        if (!TransferChecks.isCanonicalUuid(transferId)) {
            Log.w(TAG, "Rejected FILE_PART from $senderId: invalid transfer ID")
            return
        }
        val transfer = dao.getFileTransfer(transferId)
        if (transfer == null) {
            Log.w(TAG, "Received FILE_PART for unknown transfer: $transferId")
            return
        }
        // Only the contact who sent the header may add parts, and never to our own outgoing transfer
        if (transfer.isOutgoing || transfer.peerDeviceId != senderId) {
            Log.w(TAG, "Rejected FILE_PART from $senderId: transfer belongs to someone else")
            return
        }
        // A late duplicate after assembly must not restart (and then fail) a finished file
        if (transfer.status == "COMPLETE") return

        val partIndex = part.part_index
        val partBytes = part.data_.toByteArray()
        if (!TransferChecks.isValidPart(transferId, partIndex, transfer.partCount, partBytes.size)) {
            Log.w(TAG, "Rejected FILE_PART $partIndex for $transferId: index or size out of range")
            return
        }

        // Write part to disk
        val partFile = File(getTransferTempDir(transferId), "part_$partIndex")
        partFile.parentFile?.mkdirs()
        val alreadyExisted = partFile.exists()
        partFile.writeBytes(partBytes)

        if (!alreadyExisted) {
            dao.incrementReceivedParts(transferId)
        }

        // Re-query to get updated count
        val updatedTransfer = dao.getFileTransfer(transferId) ?: return

        // Update progress (batched — every N parts or when complete)
        val receivedParts = updatedTransfer.receivedParts
        if (receivedParts % PROGRESS_UPDATE_INTERVAL == 0 || receivedParts >= updatedTransfer.partCount) {
            val progress = receivedParts.toFloat() / updatedTransfer.partCount
            dao.updateTransferProgress(updatedTransfer.messageId, progress)
        }

        // Check if all parts received
        if (updatedTransfer.receivedParts >= updatedTransfer.partCount) {
            assembleFile(updatedTransfer)
        }
    }

    /**
     * Assemble all received parts into the final file.
     */
    private suspend fun assembleFile(transfer: FileTransferEntity) {
        try {
            val outputFile = saveFileLocally(
                transfer.transferId,
                transfer.fileName,
                null // we'll write manually from parts
            )

            FileOutputStream(outputFile).use { out ->
                for (i in 0 until transfer.partCount) {
                    val partFile = File(getTransferTempDir(transfer.transferId), "part_$i")
                    if (partFile.exists()) {
                        out.write(partFile.readBytes())
                    } else {
                        Log.e(TAG, "Missing part $i for transfer ${transfer.transferId}")
                        dao.updateTransferStatus(transfer.transferId, "FAILED")
                        return
                    }
                }
            }

            // The parts must add up to exactly the size the header announced
            if (outputFile.length() != transfer.fileSize) {
                Log.e(TAG, "Size mismatch for ${transfer.transferId}: got ${outputFile.length()}, expected ${transfer.fileSize}")
                outputFile.delete()
                dao.updateTransferStatus(transfer.transferId, "FAILED")
                return
            }

            // Clean up temp parts
            getTransferTempDir(transfer.transferId).deleteRecursively()

            // Update records
            dao.completeFileMessage(transfer.messageId, outputFile.absolutePath, 0)
            dao.updateTransferStatus(transfer.transferId, "COMPLETE")

            Log.d(TAG, "File assembled: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to assemble file for transfer ${transfer.transferId}", e)
            dao.updateTransferStatus(transfer.transferId, "FAILED")
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun getTransferTempDir(transferId: String): File {
        return File(context.cacheDir, "transfers/$transferId")
    }

    private fun saveFileLocally(transferId: String, fileName: String, data: ByteArray?): File {
        val dir = File(context.filesDir, "received_files")
        dir.mkdirs()
        val file = File(dir, "${transferId}_${sanitizeFileName(fileName)}")
        if (data != null) {
            file.writeBytes(data)
        }
        return file
    }

    private fun saveThumbnailLocally(transferId: String, thumbnailBytes: ByteArray): String {
        val dir = File(context.filesDir, "thumbnails")
        dir.mkdirs()
        val file = File(dir, "${transferId}_thumb.jpg")
        file.writeBytes(thumbnailBytes)
        return file.absolutePath
    }

    /**
     * Generate a small JPEG thumbnail from image bytes.
     */
    private fun generateThumbnail(imageBytes: ByteArray): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            // Calculate sample size for efficient decoding
            var sampleSize = 1
            while (origW / sampleSize > THUMBNAIL_MAX_DIM * 2 || origH / sampleSize > THUMBNAIL_MAX_DIM * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return null

            // Scale to thumbnail size
            val scale = minOf(
                THUMBNAIL_MAX_DIM.toFloat() / bitmap.width,
                THUMBNAIL_MAX_DIM.toFloat() / bitmap.height
            ).coerceAtMost(1f)
            val thumbW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val thumbH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)

            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            if (thumb !== bitmap) thumb.recycle()
            bitmap.recycle()

            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail generation failed", e)
            null
        }
    }

    /**
     * Shrinks an image to at most 1920x1920 JPEG, reading straight from [uri] in two passes
     * (size first, then a smaller decode), so a huge photo is never loaded whole.
     * Returns null when it can't be decoded as an image (it's then sent as a plain file).
     */
    private fun compressImage(uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // A bounds-only decode returns no bitmap, just fills in outWidth/outHeight
            val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
            if (opened != true || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 1920 || bounds.outHeight / sampleSize > 1920) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return null

            val scale = minOf(1920f / bitmap.width, 1920f / bitmap.height).coerceAtMost(1f)
            val finalW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val finalH = (bitmap.height * scale).toInt().coerceAtLeast(1)

            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, finalW, finalH, true) else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)

            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Image too large to compress", e)
            null
        }
    }

    fun getFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    /** Size the provider reports for [uri], or null when it doesn't say. */
    fun getFileSize(uri: Uri): Long? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex)
            }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }
}
