package com.aarushchaudhary.comlink.data

import android.content.ContentResolver
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
import kotlin.math.ceil

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

    /**
     * Send a file to a peer. Splits the file into parts and sends each as
     * an encrypted envelope. Progress is updated in Room after each part.
     */
    suspend fun sendFile(
        peerId: String,
        uri: Uri,
        messageId: String
    ): Boolean {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(contentResolver, uri) ?: "file"
        var fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: run {
                Log.e(TAG, "Cannot read file: $uri")
                return false
            }

        // Speed optimization: compress images before sending
        if (mimeType.startsWith("image/")) {
            fileBytes = compressImage(fileBytes)
        }

        if (fileBytes.size > MAX_FILE_SIZE) {
            Log.e(TAG, "File too large: ${fileBytes.size} bytes (max $MAX_FILE_SIZE)")
            return false
        }

        val transferId = UUID.randomUUID().toString()
        val partCount = ceil(fileBytes.size.toDouble() / PART_SIZE).toInt().coerceAtLeast(1)

        // Generate thumbnail for images
        val thumbnailBytes = if (mimeType.startsWith("image/")) {
            generateThumbnail(fileBytes)
        } else null

        // Save sender-side transfer record
        dao.insertFileTransfer(FileTransferEntity(
            transferId = transferId,
            messageId = messageId,
            peerDeviceId = peerId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = fileBytes.size.toLong(),
            partCount = partCount,
            isOutgoing = true
        ))

        // Also save the file locally so the sender can view it
        val localFile = saveFileLocally(transferId, fileName, fileBytes)
        dao.completeFileMessage(messageId, localFile.absolutePath, 0) // status stays WAIT until BLE confirms

        if (thumbnailBytes != null) {
            val thumbPath = saveThumbnailLocally(transferId, thumbnailBytes)
            dao.updateThumbnailPath(messageId, thumbPath)
        }

        // Send FILE_HEADER envelope (use broadcast — small, needs reliability)
        val header = FileHeader(
            transfer_id = transferId,
            file_name = fileName,
            mime_type = mimeType,
            file_size = fileBytes.size.toLong(),
            part_count = partCount,
            thumbnail = thumbnailBytes?.let { okio.ByteString.of(*it) } ?: okio.ByteString.EMPTY,
            message_id = messageId
        )

        val headerSent = sender.send(peerId, header.encode(), contentType = CONTENT_TYPE_FILE_HEADER)
        if (!headerSent) {
            Log.e(TAG, "Failed to send FILE_HEADER for transfer $transferId")
            dao.updateTransferStatus(transferId, "FAILED")
            return false
        }

        // Reserve counters upfront for all parts to avoid per-part lock acquisition
        val counters = sender.reserveCounters(peerId, partCount) ?: run {
            Log.e(TAG, "Failed to reserve counters for transfer $transferId")
            dao.updateTransferStatus(transferId, "FAILED")
            return false
        }

        // Send FILE_PART envelopes sequentially — direct + no-response for speed
        for (i in 0 until partCount) {
            val start = i * PART_SIZE
            val end = minOf(start + PART_SIZE, fileBytes.size)
            val partData = fileBytes.copyOfRange(start, end)

            val part = FilePart(
                transfer_id = transferId,
                part_index = i,
                data_ = okio.ByteString.of(*partData)
            )

            val partSent = sender.sendReserved(peerId, part.encode(), counters[i], CONTENT_TYPE_FILE_PART)

            if (!partSent) {
                Log.e(TAG, "Failed to send FILE_PART $i/$partCount for transfer $transferId")
                dao.updateTransferStatus(transferId, "FAILED")
                dao.updateLastSentPartIndex(transferId, i)
                return false
            }

            // Track last sent part index
            dao.updateLastSentPartIndex(transferId, i + 1)

            // Update progress in the message (batched — every N parts or last part)
            if (i % PROGRESS_UPDATE_INTERVAL == 0 || i == partCount - 1) {
                val progress = (i + 1).toFloat() / partCount
                dao.updateTransferProgress(messageId, progress)
            }
        }

        dao.updateTransferStatus(transferId, "COMPLETE")
        dao.updateMessageStatus(messageId, 1) // SENT
        return true
    }

    /**
     * Jumpstarts / resumes a stopped or stuck outgoing file transfer.
     * Uses RandomAccessFile to only read the parts that need resending.
     */
    suspend fun resumeOutgoingTransfer(
        transferId: String,
        peerId: String,
        messageId: String,
        filePath: String,
        partCount: Int,
        startPart: Int
    ) {
        val remainingParts = partCount - startPart
        if (remainingParts <= 0) {
            dao.updateTransferStatus(transferId, "COMPLETE")
            dao.updateMessageStatus(messageId, 1)
            return
        }

        Log.i(TAG, "Jumpstarting transfer $transferId from part $startPart ($remainingParts remaining)")
        dao.updateTransferStatus(transferId, "IN_PROGRESS")

        // Reserve counters upfront for all remaining parts
        val counters = sender.reserveCounters(peerId, remainingParts) ?: run {
            Log.e(TAG, "Jumpstart failed to reserve counters for $transferId")
            dao.updateTransferStatus(transferId, "FAILED")
            return
        }

        val raf = try { RandomAccessFile(filePath, "r") } catch (e: Exception) {
            Log.e(TAG, "Cannot open file for jumpstart: $filePath", e)
            dao.updateTransferStatus(transferId, "FAILED")
            return
        }

        try {
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
                val partSent = sender.sendReserved(peerId, part.encode(), counters[counterIdx], CONTENT_TYPE_FILE_PART)

                if (!partSent) {
                    Log.e(TAG, "Jumpstart failed to send FILE_PART $i/$partCount for transfer $transferId")
                    dao.updateTransferStatus(transferId, "FAILED")
                    dao.updateLastSentPartIndex(transferId, i)
                    return
                }

                dao.updateLastSentPartIndex(transferId, i + 1)

                if (counterIdx % PROGRESS_UPDATE_INTERVAL == 0 || i == partCount - 1) {
                    val progress = (i + 1).toFloat() / partCount
                    dao.updateTransferProgress(messageId, progress)
                }
            }

            dao.updateTransferStatus(transferId, "COMPLETE")
            dao.updateMessageStatus(messageId, 1) // SENT
        } finally {
            raf.close()
        }
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
     * Compress an image to max 1920x1920 to significantly reduce file size and transfer time.
     */
    private fun compressImage(imageBytes: ByteArray): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            
            var sampleSize = 1
            while (options.outWidth / sampleSize > 1920 || options.outHeight / sampleSize > 1920) {
                sampleSize *= 2
            }
            
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions) 
                ?: return imageBytes // fallback
                
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
            imageBytes
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }
}
