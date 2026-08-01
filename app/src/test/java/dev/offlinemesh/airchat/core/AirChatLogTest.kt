package dev.offlinemesh.airchat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirChatLogTest {
    @Test
    fun formatsOneLineDiagnosticMessage() {
        val formatted = AirChatLog.format("Transport Status", "LAN\n discovery   failed: 3")

        assertEquals("transport-status: LAN discovery failed: 3", formatted)
        assertFalse(formatted.contains("\n"))
    }

    @Test
    fun classifiesFailureDetailsAsError() {
        assertEquals(
            AirChatLogLevel.Error,
            AirChatLog.levelFor("transport", "LAN Failed: LAN discovery failed: 3")
        )
    }

    @Test
    fun classifiesDroppedPacketsAsWarnings() {
        assertEquals(
            AirChatLogLevel.Warning,
            AirChatLog.levelFor("block", "dropped Chat from abc123")
        )
    }

    @Test
    fun throwableLabelIncludesTypeWithoutStackTrace() {
        val label = AirChatLog.throwableLabel(IllegalStateException("socket\nclosed"))

        assertEquals("IllegalStateException: socket closed", label)
        assertFalse(label.contains("\n"))
        assertTrue(label.length < 120)
    }
}
