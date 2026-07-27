package dev.offlinemesh.airchat.model

import dev.offlinemesh.airchat.protocol.MeshPacket
import kotlinx.serialization.Serializable

@Serializable
data class CourierPacket(
    val packet: MeshPacket,
    val expiresAt: Long
)
