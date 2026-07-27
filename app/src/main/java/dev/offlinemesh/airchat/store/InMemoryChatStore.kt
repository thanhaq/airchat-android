package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.OutboxItem

class InMemoryChatStore : ChatStore {
    private var messages = emptyList<ChatMessage>()
    private var outbox = emptyList<OutboxItem>()

    override fun loadMessages(): List<ChatMessage> = messages

    override fun saveMessages(messages: List<ChatMessage>) {
        this.messages = messages
    }

    override fun loadOutbox(): List<OutboxItem> = outbox

    override fun saveOutbox(items: List<OutboxItem>) {
        outbox = items
    }

    override fun clear() {
        messages = emptyList()
        outbox = emptyList()
    }
}
