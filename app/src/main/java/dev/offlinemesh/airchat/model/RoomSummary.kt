package dev.offlinemesh.airchat.model

data class RoomSummary(
    val channel: String,
    val isSelected: Boolean,
    val isPrivate: Boolean,
    val isPinned: Boolean,
    val messageCount: Int,
    val unreadCount: Int,
    val fileCount: Int,
    val lastActivityAt: Long
)
