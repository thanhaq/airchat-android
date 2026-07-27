package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.PrivateRoomStatus
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.RoomSummary

object RoomDirectory {
    fun summarize(
        messages: List<ChatMessage>,
        files: List<ReceivedFile>,
        privateRooms: Map<String, PrivateRoomStatus>,
        knownRooms: Set<String>,
        pinnedRooms: Set<String>,
        selectedChannel: String,
        readAtByRoom: Map<String, Long>
    ): List<RoomSummary> {
        val roomNames = linkedSetOf(DEFAULT_ROOM)
        roomNames += knownRooms.filter(::isRoomChannel)
        roomNames += pinnedRooms.filter(::isRoomChannel)
        roomNames += privateRooms.keys.filter(::isRoomChannel)
        roomNames += messages.map { it.channel }.filter(::isRoomChannel)
        roomNames += files.map { it.channel }.filter(::isRoomChannel)
        roomNames += selectedChannel

        return roomNames
            .filter(::isRoomChannel)
            .map { room ->
                val roomMessages = messages.filter { it.channel == room }
                val roomFiles = files.filter { it.channel == room }
                val readAt = readAtByRoom[room] ?: Long.MAX_VALUE
                RoomSummary(
                    channel = room,
                    isSelected = room == selectedChannel,
                    isPrivate = room in privateRooms,
                    isPinned = room in pinnedRooms,
                    messageCount = roomMessages.size,
                    unreadCount = if (room == selectedChannel) {
                        0
                    } else {
                        roomMessages.count { message -> !message.isLocal && message.createdAt > readAt }
                    },
                    fileCount = roomFiles.size,
                    lastActivityAt = maxOf(
                        roomMessages.maxOfOrNull { it.createdAt } ?: 0L,
                        roomFiles.maxOfOrNull { it.receivedAt } ?: 0L
                    )
                )
            }
            .sortedWith(
                compareByDescending<RoomSummary> { it.channel == DEFAULT_ROOM }
                    .thenByDescending { it.isSelected }
                    .thenByDescending { it.isPinned }
                    .thenByDescending { it.lastActivityAt }
                    .thenBy { it.channel }
            )
    }

    private fun isRoomChannel(channel: String): Boolean =
        channel.isNotBlank() && !channel.startsWith(DIRECT_PREFIX)

    private const val DEFAULT_ROOM = "lobby"
    private const val DIRECT_PREFIX = "dm:"
}
