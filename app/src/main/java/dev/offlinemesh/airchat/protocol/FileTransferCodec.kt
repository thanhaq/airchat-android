package dev.offlinemesh.airchat.protocol

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class FileTransferPlan(
    val manifest: FileManifest,
    val chunks: List<FileChunk>
)

object FileTransferCodec {
    fun createPlan(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        transferId: String = UUID.randomUUID().toString()
    ): FileTransferPlan {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "chunkSize must be between 1 and $MAX_CHUNK_SIZE" }
        require(bytes.size <= MAX_FILE_BYTES) { "file is larger than $MAX_FILE_BYTES bytes" }

        val chunks = bytes.asList()
            .chunked(chunkSize)
            .mapIndexed { index, chunk ->
                FileChunk(
                    transferId = transferId,
                    index = index,
                    data = encode(chunk.toByteArray())
                )
            }
        val manifest = FileManifest(
            transferId = transferId,
            fileName = sanitizeFileName(fileName),
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            totalBytes = bytes.size,
            sha256 = sha256(bytes),
            chunkSize = chunkSize,
            totalChunks = chunks.size
        )
        return FileTransferPlan(manifest = manifest, chunks = chunks)
    }

    fun reassemble(manifest: FileManifest, chunks: List<FileChunk>): ByteArray? {
        if (manifest.totalBytes > MAX_FILE_BYTES) return null
        if (manifest.chunkSize !in 1..MAX_CHUNK_SIZE) return null
        if (chunks.size != manifest.totalChunks) return null
        if (chunks.any { it.transferId != manifest.transferId }) return null
        val sorted = chunks.sortedBy { it.index }
        if (sorted.map { it.index } != (0 until manifest.totalChunks).toList()) return null

        val bytes = sorted.flatMap { decode(it.data).asIterable() }.toByteArray()
        if (bytes.size != manifest.totalBytes) return null
        if (sha256(bytes) != manifest.sha256) return null
        return bytes
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
            .ifBlank { "airchat-file" }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private const val DEFAULT_CHUNK_SIZE = 32 * 1024
    private const val MAX_CHUNK_SIZE = DEFAULT_CHUNK_SIZE
    private const val MAX_FILE_BYTES = 10 * 1024 * 1024
}
