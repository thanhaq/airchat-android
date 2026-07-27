package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.ReceivedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundAlertTrackerTest {
    @Test
    fun doesNotAlertForExistingHistory() {
        val tracker = BackgroundAlertTracker()
        val existing = message(id = "old-message")

        tracker.markExisting(messages = listOf(existing), files = emptyList())

        assertNull(tracker.consumeMessageAlert(listOf(existing), shouldNotify = true))
    }

    @Test
    fun alertsForNewRemoteMessageWithoutBodyContent() {
        val tracker = BackgroundAlertTracker()
        val incoming = message(id = "new-message", body = "secret field note")

        val alert = tracker.consumeMessageAlert(listOf(incoming), shouldNotify = true)

        assertEquals("New AirChat message", alert?.title)
        assertEquals("alice in #lobby", alert?.body)
        assertFalse(alert?.body?.contains("secret field note") ?: true)
    }

    @Test
    fun marksMessageSeenWhenNotificationsAreDisabled() {
        val tracker = BackgroundAlertTracker()
        val incoming = message(id = "seen-while-open")

        assertNull(tracker.consumeMessageAlert(listOf(incoming), shouldNotify = false))

        assertNull(tracker.consumeMessageAlert(listOf(incoming), shouldNotify = true))
    }

    @Test
    fun ignoresLocalMessages() {
        val tracker = BackgroundAlertTracker()

        assertNull(
            tracker.consumeMessageAlert(
                messages = listOf(message(id = "local-message", isLocal = true)),
                shouldNotify = true
            )
        )
    }

    @Test
    fun alertsForNewReceivedFileWithoutFileName() {
        val tracker = BackgroundAlertTracker()
        val file = receivedFile(id = "file-1", fileName = "private-notes.txt")

        val alert = tracker.consumeFileAlert(listOf(file), shouldNotify = true)

        assertEquals("New AirChat file", alert?.title)
        assertEquals("alice in #ops", alert?.body)
        assertFalse(alert?.body?.contains("private-notes.txt") ?: true)
    }

    private fun message(
        id: String,
        body: String = "hello",
        isLocal: Boolean = false,
        state: DeliveryState = DeliveryState.Verified
    ) = ChatMessage(
        id = id,
        channel = "lobby",
        senderId = "peer-alice",
        senderName = "alice",
        body = body,
        createdAt = 1_000L,
        state = state,
        isLocal = isLocal
    )

    private fun receivedFile(
        id: String,
        fileName: String
    ) = ReceivedFile(
        id = id,
        fileName = fileName,
        mimeType = "text/plain",
        totalBytes = 12,
        sha256 = "abc123",
        senderId = "peer-alice",
        senderName = "alice",
        channel = "ops",
        receivedAt = 1_000L,
        bytes = "hello".toByteArray()
    )
}
