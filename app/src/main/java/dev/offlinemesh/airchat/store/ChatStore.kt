package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.OutboxItem

interface ChatStore {
    fun loadMessages(): List<ChatMessage>
    fun saveMessages(messages: List<ChatMessage>)
    fun loadOutbox(): List<OutboxItem>
    fun saveOutbox(items: List<OutboxItem>)
    fun clear()
}
