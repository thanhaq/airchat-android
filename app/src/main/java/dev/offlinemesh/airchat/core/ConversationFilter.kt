package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.ReceivedFile

sealed interface Conversation {
    data class Room(val channel: String) : Conversation
    data class Direct(val peerId: String) : Conversation
}

object ConversationFilter {
    fun apply(messages: List<ChatMessage>, conversation: Conversation): List<ChatMessage> {
        return when (conversation) {
            is Conversation.Room -> messages.filter { message ->
                message.channel == conversation.channel && !message.channel.startsWith(DIRECT_PREFIX)
            }

            is Conversation.Direct -> messages.filter { message ->
                message.channel == "$DIRECT_PREFIX${conversation.peerId}"
            }
        }
    }

    fun applyFiles(files: List<ReceivedFile>, conversation: Conversation): List<ReceivedFile> {
        return when (conversation) {
            is Conversation.Room -> files.filter { file ->
                file.channel == conversation.channel && !file.channel.startsWith(DIRECT_PREFIX)
            }

            is Conversation.Direct -> files.filter { file ->
                file.channel == "$DIRECT_PREFIX${conversation.peerId}"
            }
        }
    }

    private const val DIRECT_PREFIX = "dm:"
}
