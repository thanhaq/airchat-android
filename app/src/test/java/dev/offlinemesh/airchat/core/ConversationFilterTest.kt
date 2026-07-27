package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.ReceivedFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFilterTest {
    @Test
    fun roomConversationExcludesDirectMessagesAndOtherRooms() {
        val messages = listOf(
            message(id = "one", channel = "lobby"),
            message(id = "two", channel = "ops"),
            message(id = "three", channel = "dm:peer-a")
        )

        val visible = ConversationFilter.apply(messages, Conversation.Room("lobby"))

        assertEquals(listOf("one"), visible.map { it.id })
    }

    @Test
    fun directConversationShowsOnlyThatPeerThread() {
        val messages = listOf(
            message(id = "one", channel = "lobby"),
            message(id = "two", channel = "dm:peer-a"),
            message(id = "three", channel = "dm:peer-b")
        )

        val visible = ConversationFilter.apply(messages, Conversation.Direct("peer-a"))

        assertEquals(listOf("two"), visible.map { it.id })
    }

    @Test
    fun fileFilteringFollowsConversation() {
        val files = listOf(
            file(id = "one", channel = "lobby"),
            file(id = "two", channel = "dm:peer-a")
        )

        assertEquals(listOf("one"), ConversationFilter.applyFiles(files, Conversation.Room("lobby")).map { it.id })
        assertEquals(listOf("two"), ConversationFilter.applyFiles(files, Conversation.Direct("peer-a")).map { it.id })
    }

    private fun message(id: String, channel: String) = ChatMessage(
        id = id,
        channel = channel,
        senderId = "sender",
        senderName = "sender",
        body = "body",
        createdAt = 1L,
        state = DeliveryState.Sent
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
        receivedAt = 1L,
        bytes = byteArrayOf(1)
    )
}
