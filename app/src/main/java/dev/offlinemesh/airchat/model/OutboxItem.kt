package dev.offlinemesh.airchat.model

import dev.offlinemesh.airchat.protocol.MeshPacket
import kotlinx.serialization.Serializable

@Serializable
data class OutboxItem(
    val id: String,
    val packet: MeshPacket,
    val targetPeerId: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = createdAt
)
