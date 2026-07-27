package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.PrivateRoomStatus
import dev.offlinemesh.airchat.model.ReceivedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDirectoryTest {
    @Test
    fun summarizesRoomsAndExcludesDirectThreads() {
        val rooms = RoomDirectory.summarize(
            messages = listOf(
                message(id = "one", channel = "lobby", createdAt = 10L),
                message(id = "two", channel = "ops", createdAt = 20L),
                message(id = "dm", channel = "dm:peer-a", createdAt = 30L)
            ),
            files = listOf(file(id = "map", channel = "ops")),
            privateRooms = mapOf(
                "ops" to PrivateRoomStatus(
                    channel = "ops",
                    verificationCode = "ABCD-1234-EF56",
                    strengthLabel = "strong"
                )
            ),
            knownRooms = setOf("lobby", "field"),
            pinnedRooms = emptySet(),
            selectedChannel = "lobby",
            readAtByRoom = mapOf("ops" to 5L)
        )

        assertEquals(listOf("lobby", "ops", "field"), rooms.map { it.channel })
        assertTrue(rooms.first { it.channel == "ops" }.isPrivate)
        assertEquals(1, rooms.first { it.channel == "ops" }.unreadCount)
        assertEquals(1, rooms.first { it.channel == "ops" }.fileCount)
    }

    @Test
    fun selectedRoomHasNoUnreadCount() {
        val rooms = RoomDirectory.summarize(
            messages = listOf(message(id = "remote", channel = "ops", createdAt = 20L)),
            files = emptyList(),
            privateRooms = emptyMap(),
            knownRooms = setOf("ops"),
            pinnedRooms = emptySet(),
            selectedChannel = "ops",
            readAtByRoom = emptyMap()
        )

        assertEquals(0, rooms.single { it.channel == "ops" }.unreadCount)
        assertTrue(rooms.single { it.channel == "ops" }.isSelected)
    }

    @Test
    fun localMessagesDoNotCreateUnreadCounts() {
        val rooms = RoomDirectory.summarize(
            messages = listOf(message(id = "local", channel = "ops", createdAt = 20L, isLocal = true)),
            files = emptyList(),
            privateRooms = emptyMap(),
            knownRooms = setOf("ops"),
            pinnedRooms = emptySet(),
            selectedChannel = "lobby",
            readAtByRoom = mapOf("ops" to 1L)
        )

        assertEquals(0, rooms.single { it.channel == "ops" }.unreadCount)
    }

    @Test
    fun pinnedRoomsStayVisibleAndSortBeforeUnpinnedActivity() {
        val rooms = RoomDirectory.summarize(
            messages = listOf(
                message(id = "ops", channel = "ops", createdAt = 30L),
                message(id = "alerts", channel = "alerts", createdAt = 40L)
            ),
            files = emptyList(),
            privateRooms = emptyMap(),
            knownRooms = setOf("ops"),
            pinnedRooms = setOf("alerts"),
            selectedChannel = "field",
            readAtByRoom = emptyMap()
        )

        assertEquals(listOf("lobby", "field", "alerts", "ops"), rooms.map { it.channel })
        assertTrue(rooms.single { it.channel == "alerts" }.isPinned)
    }

    @Test
    fun manualRoomOrderSortsBeforePinnedAndRecentFallbacks() {
        val rooms = RoomDirectory.summarize(
            messages = listOf(
                message(id = "ops", channel = "ops", createdAt = 30L),
                message(id = "alerts", channel = "alerts", createdAt = 50L),
                message(id = "field", channel = "field", createdAt = 10L)
            ),
            files = emptyList(),
            privateRooms = emptyMap(),
            knownRooms = setOf("ops", "alerts", "field"),
            pinnedRooms = setOf("alerts"),
            roomOrder = listOf("field", "ops"),
            selectedChannel = "lobby",
            readAtByRoom = emptyMap()
        )

        assertEquals(listOf("lobby", "field", "ops", "alerts"), rooms.map { it.channel })
    }

    private fun message(
        id: String,
        channel: String,
        createdAt: Long,
        isLocal: Boolean = false
    ) = ChatMessage(
        id = id,
        channel = channel,
        senderId = "sender",
        senderName = "sender",
        body = "body",
        createdAt = createdAt,
        state = DeliveryState.Verified,
        isLocal = isLocal
    )

    private fun file(id: String, channel: String) = ReceivedFile(
        id = id,
        fileName = "$id.txt",
        mimeType = "text/plain",
        totalBytes = 1,
        sha256 = "sha",
        senderId = "sender",
        senderName = "sender",
        channel = channel,
        receivedAt = 15L,
        bytes = byteArrayOf(1)
    )
}
