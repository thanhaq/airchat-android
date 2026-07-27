package dev.offlinemesh.airchat.protocol

import kotlinx.serialization.Serializable

@Serializable
data class MeshPacket(
    val id: String,
    val type: PacketType,
    val originId: String,
    val originName: String,
    val originPublicKey: String,
    val createdAt: Long,
    val ttl: Int,
    val channel: String,
    val payload: String,
    val signature: String? = null,
    val path: List<String> = emptyList()
)

@Serializable
enum class PacketType {
    Hello,
    Chat,
    Direct,
    FileManifest,
    FileChunk,
    Ack
}

@Serializable
data class DirectPayload(
    val recipientId: String,
    val ephemeralPublicKey: String,
    val nonce: String,
    val ciphertext: String
)

@Serializable
data class DirectEnvelope(
    val kind: DirectKind,
    val body: String
)

@Serializable
enum class DirectKind {
    Text,
    FileManifest,
    FileChunk
}

@Serializable
data class FileManifest(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Int,
    val sha256: String,
    val chunkSize: Int,
    val totalChunks: Int
)

@Serializable
data class FileChunk(
    val transferId: String,
    val index: Int,
    val data: String
)

@Serializable
data class AckPayload(
    val packetId: String,
    val receivedAt: Long,
    val status: AckStatus
)

@Serializable
enum class AckStatus {
    Received,
    Verified,
    Unverified
}
