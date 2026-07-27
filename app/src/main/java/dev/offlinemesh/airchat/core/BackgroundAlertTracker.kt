package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.ReceivedFile

data class BackgroundAlert(
    val title: String,
    val body: String
)

class BackgroundAlertTracker {
    private val seenMessageIds = linkedSetOf<String>()
    private val seenFileIds = linkedSetOf<String>()

    fun markExisting(messages: List<ChatMessage>, files: List<ReceivedFile>) {
        rememberMessages(messages)
        rememberFiles(files)
    }

    fun consumeMessageAlert(messages: List<ChatMessage>, shouldNotify: Boolean): BackgroundAlert? {
        val candidates = messages
            .filter { message -> !message.isLocal && message.id !in seenMessageIds }
            .filterNot { message -> message.state == DeliveryState.Pending || message.state == DeliveryState.Sent }
        rememberMessages(messages)
        if (!shouldNotify || candidates.isEmpty()) return null

        val latest = candidates.maxBy { it.createdAt }
        return BackgroundAlert(
            title = "New AirChat message",
            body = "${latest.senderName} in ${conversationLabel(latest.channel)}"
        )
    }

    fun consumeFileAlert(files: List<ReceivedFile>, shouldNotify: Boolean): BackgroundAlert? {
        val candidates = files.filter { file -> file.id !in seenFileIds }
        rememberFiles(files)
        if (!shouldNotify || candidates.isEmpty()) return null

        val latest = candidates.maxBy { it.receivedAt }
        return BackgroundAlert(
            title = "New AirChat file",
            body = "${latest.senderName} in ${conversationLabel(latest.channel)}"
        )
    }

    private fun rememberMessages(messages: List<ChatMessage>) {
        messages.forEach { seenMessageIds += it.id }
        trimSeenIds(seenMessageIds)
    }

    private fun rememberFiles(files: List<ReceivedFile>) {
        files.forEach { seenFileIds += it.id }
        trimSeenIds(seenFileIds)
    }

    private fun trimSeenIds(ids: LinkedHashSet<String>) {
        while (ids.size > MAX_SEEN_IDS) {
            val oldest = ids.firstOrNull() ?: return
            ids.remove(oldest)
        }
    }

    private fun conversationLabel(channel: String): String {
        return if (channel.startsWith(DIRECT_CHANNEL_PREFIX)) {
            "DM"
        } else {
            "#$channel"
        }
    }

    private companion object {
        const val DIRECT_CHANNEL_PREFIX = "dm:"
        const val MAX_SEEN_IDS = 1_024
    }
}
