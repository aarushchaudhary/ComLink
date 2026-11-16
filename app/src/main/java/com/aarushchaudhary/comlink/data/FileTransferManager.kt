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
import com.aarushchaudhary.comlink.proto.FileStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
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
    private val sender: EncryptedSender,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "FileTransfer"
        const val PART_SIZE = TransferChecks.PART_SIZE  // ~100 KB per part (stays within 128 KB BLE envelope limit)
        const val MAX_FILE_SIZE = TransferChecks.MAX_FILE_SIZE  // 10 MB hard limit
        const val CONTENT_TYPE_MESSAGE = 0
        const val CONTENT_TYPE_FILE_HEADER = 1
        const val CONTENT_TYPE_FILE_PART = 2
        const val CONTENT_TYPE_PRESENCE = 3
        const val CONTENT_TYPE_FILE_STATUS = 4

        /** Sender: wait this long for the receiver's status before asking for it. */
        private const val STATUS_WAIT_MS = 15_000L
        /** Sender: wait this long for an answer to a status request. */
        private const val REQUEST_WAIT_MS = 10_000L
        /** Sender: resend rounds before a transfer is marked failed. */
        private const val MAX_DELIVERY_ROUNDS = 5
        /** Receiver: report status when no part has arrived for this long. */
        private const val STALL_REPORT_MS = 10_000L
        const val THUMBNAIL_MAX_DIM = 128
        const val THUMBNAIL_QUALITY = 50
        private const val PROGRESS_UPDATE_INTERVAL = 5 // Update progress every N parts
    }

    // Sender side: the latest FILE_STATUS per transfer we're waiting on, and transfers with a running loop
    private val statusWaiters = ConcurrentHashMap<String, Channel<FileStatus>>()
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()

    // Receiver side: one "no part for a while" report timer per incoming transfer
    private val stallTimers = ConcurrentHashMap<String, Job>()

    /** Outcome of [sendFile], so the caller can tell the user why a file didn't go out. */
    enum class SendFileResult { SENT, TOO_LARGE, UNREADABLE, NOT_SENT }

    private sealed class ReadResult {
        /** [asJpeg]: an image that was shrunk and re-encoded as JPEG. */
        class Ok(val bytes: ByteArray, val asJpeg: Boolean = false) : ReadResult()
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
        var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        var fileName = getFileName(uri) ?: "file"
        val transferId = messageId

        val fileBytes = when (val read = readForSending(uri, mimeType)) {
            is ReadResult.Ok -> {
                if (read.asJpeg && mimeType != "image/jpeg") {
                    // The bytes are JPEG now: name and type must say so, or a saved copy would be
                    // e.g. "photo.png" holding JPEG data
                    mimeType = "image/jpeg"
                    fileName = fileName.substringBeforeLast('.', fileName) + ".jpg"
                }
                read.bytes
            }
            ReadResult.TooLarge -> {
                Log.e(TAG, "File too large to send: $uri (max $MAX_FILE_SIZE bytes)")
                return SendFileResult.TOO_LARGE
            }
            ReadResult.Unreadable -> {
                Log.e(TAG, "Cannot read file: $uri")
                return SendFileResult.UNREADABLE
            }
        }

        val partCount = TransferChecks.expectedPartCount(fileBytes.size.toLong())
        // Our own bubble shows what was actually sent
        dao.updateFileMeta(messageId, fileName, mimeType, fileBytes.size.toLong())

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

        val delivered = withActiveTransfer(transferId) {
            transmit(transfer, localFile.absolutePath, startPart = 0, thumbnailBytes = thumbnailBytes)
        }
        return if (delivered) {
            SendFileResult.SENT
        } else {
            SendFileResult.NOT_SENT
        }
    }

    /**
     * Jumpstarts a stuck outgoing file. It first asks the receiver which parts it holds and sends
     * only the missing ones. If the receiver doesn't answer, or never got the header, the header
     * and the parts go again from where sending stopped (all of them if every part was sent).
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

        val thumbnailBytes = message.thumbnailPath?.let { File(it) }
            ?.takeIf { it.exists() && it.length() <= TransferChecks.MAX_THUMBNAIL_BYTES }
            ?.readBytes()

        withActiveTransfer(transfer.transferId) {
            dao.updateTransferStatus(transfer.transferId, "IN_PROGRESS")
            // 1. Ask first: the receiver may already hold most of it, or all of it
            requestStatus(transfer)
            val status = awaitStatus(transfer.transferId, REQUEST_WAIT_MS)
            when {
                status?.complete == true -> {
                    markDelivered(transfer)
                    true
                }
                status != null && status.part_count > 0 -> {
                    val missing = PartBitmap.missing(status.received.toByteArray(), transfer.partCount)
                    Log.i(TAG, "Jumpstart ${transfer.transferId}: receiver is missing ${missing.size} of ${transfer.partCount} parts")
                    if (!sendParts(transfer, filePath, missing, trackProgress = false)) {
                        failDelivery(transfer)
                        false
                    } else {
                        awaitDelivery(transfer, filePath, thumbnailBytes)
                    }
                }
                else -> {
                    // 2. No answer, or no header on their side: header + parts again
                    val startPart = if (transfer.lastSentPartIndex in 1 until transfer.partCount) transfer.lastSentPartIndex else 0
                    Log.i(TAG, "Jumpstart ${transfer.transferId}: no status, resending from part $startPart")
                    transmit(transfer, filePath, startPart, thumbnailBytes)
                }
            }
        }
    }

    /**
     * Sends the header, then parts [startPart] until the end from the local copy at [filePath],
     * then waits for the receiver to confirm every part ([awaitDelivery]). Returns true once the
     * receiver reports the file complete.
     */
    private suspend fun transmit(
        transfer: FileTransferEntity,
        filePath: String,
        startPart: Int,
        thumbnailBytes: ByteArray?
    ): Boolean {
        dao.updateTransferStatus(transfer.transferId, "IN_PROGRESS")
        if (!sendHeader(transfer, thumbnailBytes) ||
            !sendParts(transfer, filePath, (startPart until transfer.partCount).toList(), trackProgress = true)
        ) {
            failDelivery(transfer)
            return false
        }
        // Every part went out: SENT. Delivered (2) only once the receiver confirms.
        dao.raiseMessageStatus(transfer.messageId, 1)
        return awaitDelivery(transfer, filePath, thumbnailBytes)
    }

    /**
     * Waits for the receiver's FILE_STATUS and re-sends only what it is missing, for up to
     * [MAX_DELIVERY_ROUNDS] rounds. No status in time means asking for one. A receiver that
     * doesn't know the transfer gets the header and every part again.
     */
    private suspend fun awaitDelivery(transfer: FileTransferEntity, filePath: String, thumbnailBytes: ByteArray?): Boolean {
        val transferId = transfer.transferId
        repeat(MAX_DELIVERY_ROUNDS) { round ->
            // Statuses that arrived while parts were still going out are stale, except "complete"
            if (takeStaleStatus(transferId)?.complete == true) {
                markDelivered(transfer)
                return true
            }
            val status = awaitStatus(transferId, STATUS_WAIT_MS)
                ?: run {
                    requestStatus(transfer)
                    awaitStatus(transferId, REQUEST_WAIT_MS)
                }
                ?: return@repeat // no answer this round

            if (status.complete) {
                markDelivered(transfer)
                return true
            }
            val resendOk = if (status.part_count == 0) {
                Log.i(TAG, "Transfer $transferId round ${round + 1}: receiver has no header, resending all")
                sendHeader(transfer, thumbnailBytes) &&
                    sendParts(transfer, filePath, (0 until transfer.partCount).toList(), trackProgress = false)
            } else {
                val missing = PartBitmap.missing(status.received.toByteArray(), transfer.partCount)
                if (missing.isEmpty()) return@repeat // all parts there, the file is being assembled
                Log.i(TAG, "Transfer $transferId round ${round + 1}: resending ${missing.size} missing parts")
                sendParts(transfer, filePath, missing, trackProgress = false)
            }
            if (!resendOk) {
                failDelivery(transfer)
                return false
            }
        }
        Log.w(TAG, "Transfer $transferId not confirmed after $MAX_DELIVERY_ROUNDS rounds")
        failDelivery(transfer)
        return false
    }

    private suspend fun sendHeader(transfer: FileTransferEntity, thumbnailBytes: ByteArray?): Boolean {
        val header = FileHeader(
            transfer_id = transfer.transferId,
            file_name = transfer.fileName,
            mime_type = transfer.mimeType,
            file_size = transfer.fileSize,
            part_count = transfer.partCount,
            thumbnail = thumbnailBytes?.let { okio.ByteString.of(*it) } ?: okio.ByteString.EMPTY,
            message_id = transfer.messageId
        )
        val sent = sender.send(transfer.peerDeviceId, header.encode(), contentType = CONTENT_TYPE_FILE_HEADER)
        if (!sent) Log.e(TAG, "Failed to send FILE_HEADER for transfer ${transfer.transferId}")
        return sent
    }

    /**
     * Sends the listed parts from the local copy, direct + no-response for speed, with counters
     * reserved in one go. [trackProgress] (first pass only) updates the resume point and progress bar.
     */
    private suspend fun sendParts(
        transfer: FileTransferEntity,
        filePath: String,
        parts: List<Int>,
        trackProgress: Boolean
    ): Boolean {
        if (parts.isEmpty()) return true
        val transferId = transfer.transferId
        val peerId = transfer.peerDeviceId
        val partCount = transfer.partCount

        val counters = sender.reserveCounters(peerId, parts.size) ?: run {
            Log.e(TAG, "Failed to reserve counters for transfer $transferId")
            return false
        }
        val raf = try { RandomAccessFile(filePath, "r") } catch (e: Exception) {
            Log.e(TAG, "Cannot open local copy for transfer $transferId: $filePath", e)
            return false
        }
        raf.use {
            parts.forEachIndexed { n, i ->
                val start = i.toLong() * PART_SIZE
                val end = minOf(start + PART_SIZE, raf.length())
                val partData = ByteArray((end - start).toInt())
                raf.seek(start)
                raf.readFully(partData)

                val part = FilePart(transfer_id = transferId, part_index = i, data_ = okio.ByteString.of(*partData))
                if (!sender.sendReserved(peerId, part.encode(), counters[n], CONTENT_TYPE_FILE_PART)) {
                    Log.e(TAG, "Failed to send FILE_PART $i/$partCount for transfer $transferId")
                    if (trackProgress) dao.updateLastSentPartIndex(transferId, i)
                    return false
                }
                if (trackProgress) {
                    dao.updateLastSentPartIndex(transferId, i + 1)
                    // Update progress in the message (batched — every N parts or last part)
                    if (n % PROGRESS_UPDATE_INTERVAL == 0 || i == partCount - 1) {
                        dao.updateTransferProgress(transfer.messageId, (i + 1).toFloat() / partCount)
                    }
                }
            }
        }
        return true
    }

    private suspend fun requestStatus(transfer: FileTransferEntity) {
        val request = FileStatus(transfer_id = transfer.transferId, request = true)
        sender.send(transfer.peerDeviceId, request.encode(), contentType = CONTENT_TYPE_FILE_STATUS)
    }

    private suspend fun markDelivered(transfer: FileTransferEntity) {
        dao.updateTransferStatus(transfer.transferId, "COMPLETE")
        dao.updateTransferProgress(transfer.messageId, 1f)
        dao.raiseMessageStatus(transfer.messageId, 2) // Delivered (never lowers a Seen)
    }

    /** Not confirmed: back to WAIT (status 0), so the retry option shows up again. */
    private suspend fun failDelivery(transfer: FileTransferEntity) {
        dao.updateTransferStatus(transfer.transferId, "FAILED")
        dao.updateMessageStatus(transfer.messageId, 0)
    }

    private fun waiterFor(transferId: String): Channel<FileStatus> =
        statusWaiters.getOrPut(transferId) { Channel(Channel.CONFLATED) }

    private suspend fun awaitStatus(transferId: String, timeoutMs: Long): FileStatus? =
        withTimeoutOrNull(timeoutMs) { waiterFor(transferId).receive() }

    private fun takeStaleStatus(transferId: String): FileStatus? = waiterFor(transferId).tryReceive().getOrNull()

    /** One send/jumpstart loop per transfer at a time; a second tap while one runs does nothing. */
    private suspend fun withActiveTransfer(transferId: String, block: suspend () -> Boolean): Boolean {
        if (!activeTransfers.add(transferId)) return false
        return try {
            block()
        } finally {
            activeTransfers.remove(transferId)
            statusWaiters.remove(transferId)
        }
    }

    /**
     * Reads the bytes to send without ever loading more than [MAX_FILE_SIZE] + 1 bytes.
     * Images are shrunk straight from the stream; anything that can't be decoded as an image is
     * sent as a plain file, as before.
     */
    private fun readForSending(uri: Uri, mimeType: String): ReadResult {
        if (mimeType.startsWith("image/")) {
            compressImage(uri)?.let { jpeg ->
                return if (jpeg.size > MAX_FILE_SIZE) ReadResult.TooLarge else ReadResult.Ok(jpeg, asJpeg = true)
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
                // Tell the sender where we stand: done, or (if parts don't follow) what's missing
                if (existingTransfer.status == "COMPLETE") reportStatus(existingTransfer) else armStallReport(existingTransfer)
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
        // If no parts follow, report the (empty) status so the sender knows the header arrived
        dao.getFileTransfer(transferId)?.let { armStallReport(it) }
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
        // A late duplicate after assembly must not restart (and then fail) a finished file.
        // The sender evidently missed our "complete" report, so send it again.
        if (transfer.status == "COMPLETE") {
            reportStatus(transfer)
            return
        }

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
            stallTimers.remove(transferId)?.cancel()
            assembleFile(updatedTransfer)
        } else {
            // If the rest stops coming, tell the sender which parts are missing
            armStallReport(updatedTransfer)
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
            // Confirm to the sender, so it can show Delivered and stop resending
            reportStatus(transfer.copy(status = "COMPLETE"))

            Log.d(TAG, "File assembled: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to assemble file for transfer ${transfer.transferId}", e)
            dao.updateTransferStatus(transfer.transferId, "FAILED")
        }
    }

    /**
     * Handle an incoming FILE_STATUS. With `request` set it comes from a sender asking us (the
     * receiver) for our status; otherwise it is a receiver reporting on a file we are sending.
     */
    suspend fun onFileStatusReceived(senderId: String, decryptedPayload: ByteArray) {
        val status = try {
            FileStatus.ADAPTER.decode(decryptedPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode FileStatus", e)
            return
        }
        val transferId = status.transfer_id
        if (!TransferChecks.isCanonicalUuid(transferId)) return
        val transfer = dao.getFileTransfer(transferId)

        if (status.request) {
            when {
                // Never saw the header: say so (part_count 0), and the sender starts over
                transfer == null -> scope.launch(Dispatchers.IO) {
                    sender.send(senderId, FileStatus(transfer_id = transferId, part_count = 0).encode(), contentType = CONTENT_TYPE_FILE_STATUS)
                }
                !transfer.isOutgoing && transfer.peerDeviceId == senderId -> reportStatus(transfer)
                else -> Log.w(TAG, "Ignored status request from $senderId for a transfer that isn't theirs")
            }
            return
        }

        // A report about one of our outgoing transfers, from that transfer's receiver only
        if (transfer == null || !transfer.isOutgoing || transfer.peerDeviceId != senderId) return
        val waiter = statusWaiters[transferId]
        if (waiter != null) {
            waiter.trySend(status)
        } else if (status.complete) {
            // No loop waiting (it gave up, or the app restarted): a late "complete" still counts
            markDelivered(transfer)
        }
    }

    /** Sends our receiver-side status for [transfer]: which parts we hold, or that it is complete. */
    private fun reportStatus(transfer: FileTransferEntity) {
        scope.launch(Dispatchers.IO) {
            val complete = transfer.status == "COMPLETE"
            val held = if (complete) (0 until transfer.partCount).toList() else heldParts(transfer)
            val status = FileStatus(
                transfer_id = transfer.transferId,
                part_count = transfer.partCount,
                received = okio.ByteString.of(*PartBitmap.encode(transfer.partCount, held)),
                complete = complete
            )
            sender.send(transfer.peerDeviceId, status.encode(), contentType = CONTENT_TYPE_FILE_STATUS)
        }
    }

    /** (Re)starts the "no part for [STALL_REPORT_MS]" timer that reports our status once. */
    private fun armStallReport(transfer: FileTransferEntity) {
        val transferId = transfer.transferId
        stallTimers.remove(transferId)?.cancel()
        stallTimers[transferId] = scope.launch(Dispatchers.IO) {
            delay(STALL_REPORT_MS)
            stallTimers.remove(transferId)
            val current = dao.getFileTransfer(transferId) ?: return@launch
            reportStatus(current)
        }
    }

    /** Part indexes already on disk for an incoming transfer. */
    private fun heldParts(transfer: FileTransferEntity): List<Int> =
        (0 until transfer.partCount).filter { File(getTransferTempDir(transfer.transferId), "part_$it").exists() }

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

    /**
     * Save to device: copies our local copy at [filePath] to [destination], a document the user
     * just created in the system "Save as" screen. No storage permission is needed for that.
     * Returns false if anything fails (the half-written document is then left to the user).
     */
    fun exportToDevice(filePath: String, destination: Uri): Boolean {
        val source = File(filePath)
        if (!source.exists()) return false
        return try {
            val out = context.contentResolver.openOutputStream(destination, "w") ?: return false
            out.use { output -> source.inputStream().use { input -> FileCopy.copy(input, output) } }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save to device failed for $destination", e)
            false
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
