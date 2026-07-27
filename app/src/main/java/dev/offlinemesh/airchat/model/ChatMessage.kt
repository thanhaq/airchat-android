package dev.offlinemesh.airchat.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val channel: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val createdAt: Long,
    val state: DeliveryState,
    val hopCount: Int = 0,
    val isLocal: Boolean = false
)

@Serializable
enum class DeliveryState {
    Pending,
    Sent,
    Received,
    Verified,
    Unverified,
    Locked
}
