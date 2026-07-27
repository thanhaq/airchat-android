package dev.offlinemesh.airchat.model

data class ReceivedFile(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val sha256: String,
    val senderId: String,
    val senderName: String,
    val channel: String,
    val receivedAt: Long,
    val bytes: ByteArray
)
