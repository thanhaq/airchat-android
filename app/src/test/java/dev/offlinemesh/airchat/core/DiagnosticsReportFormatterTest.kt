package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.crypto.IdentityKeySecurity
import dev.offlinemesh.airchat.model.DiagnosticEvent
import dev.offlinemesh.airchat.model.TransportState
import dev.offlinemesh.airchat.model.TransportStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportFormatterTest {
    @Test
    fun formatsReadableDiagnosticsReport() {
        val report = DiagnosticsReportFormatter.format(exampleSnapshot())

        assertTrue(report.contains("AirChat diagnostics"))
        assertTrue(report.contains("Identity key: Android Keystore hardware-backed"))
        assertTrue(report.contains("Conversation: #lobby"))
        assertTrue(report.contains("Private room: on / code ABCD-1234-EF56 / strength strong"))
        assertTrue(report.contains("Power mode: conserve"))
        assertTrue(report.contains("Battery: 18% / unplugged / battery saver on"))
        assertTrue(report.contains("Rooms visible: 3"))
        assertTrue(report.contains("Rooms unread: 1"))
        assertTrue(report.contains("Rooms pinned: 2"))
        assertTrue(report.contains("Peers blocked: 1"))
        assertTrue(report.contains("Courier queue: 3"))
        assertTrue(report.contains("Courier relay: on"))
        assertTrue(report.contains("Courier retention: 15m"))
        assertTrue(report.contains("Courier quota: 32 per origin"))
        assertTrue(report.contains("- LAN: Ready (Advertising on local Wi-Fi)"))
        assertTrue(report.contains("Recent events:"))
        assertTrue(report.contains("router: started with 2 transports"))
        assertTrue(report.contains("guard: invalid ttl for Chat from abc123"))
    }

    @Test
    fun formatsStructuredJsonDiagnosticsReport() {
        val json = DiagnosticsReportFormatter.formatJson(exampleSnapshot())
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals(DiagnosticsReportFormatter.JSON_SCHEMA, root["schema"]!!.jsonPrimitive.content)
        assertEquals("0.1.0-debug", root["app"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(
            DiagnosticsReportFormatter.PROTOCOL_VERSION,
            root["protocol"]!!.jsonObject["version"]!!.jsonPrimitive.content
        )
        assertEquals("Example Phone", root["device"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("alice / peer123", root["peer"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("Android Keystore hardware-backed", root["peer"]!!.jsonObject["identityKey"]!!.jsonPrimitive.content)
        assertEquals("#lobby", root["conversation"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(root["privateRoom"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("ABCD-1234-EF56", root["privateRoom"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(2, root["counts"]!!.jsonObject["peersVisible"]!!.jsonPrimitive.int)
        assertEquals(32, root["courier"]!!.jsonObject["maxPacketsPerOrigin"]!!.jsonPrimitive.int)
        assertEquals("32 per origin", root["courier"]!!.jsonObject["quota"]!!.jsonPrimitive.content)

        val transport = root["transports"]!!.jsonArray.single().jsonObject
        assertEquals("LAN", transport["name"]!!.jsonPrimitive.content)
        assertEquals("Ready", transport["state"]!!.jsonPrimitive.content)
        assertEquals("Ready (Advertising on local Wi-Fi)", transport["label"]!!.jsonPrimitive.content)

        val events = root["recentEvents"]!!.jsonArray
        assertEquals(2, events.size)
        assertEquals("router", events.first().jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals("invalid ttl for Chat from abc123", events.last().jsonObject["detail"]!!.jsonPrimitive.content)
    }

    private fun exampleSnapshot(): DiagnosticsSnapshot {
        return DiagnosticsSnapshot(
            appVersion = "0.1.0-debug",
            protocolVersion = DiagnosticsReportFormatter.PROTOCOL_VERSION,
            device = "Example Phone",
            androidVersion = "15 / API 35",
            localPeerId = "peer123",
            nickname = "alice",
            identityKeySecurity = IdentityKeySecurity.AndroidKeyStoreHardwareBacked,
            channel = "lobby",
            privateRoomEnabled = true,
            privateRoomCode = "ABCD-1234-EF56",
            privateRoomStrength = "strong",
            directPeerName = null,
            backgroundMeshEnabled = true,
            powerMode = "conserve",
            batteryState = "18% / unplugged / battery saver on",
            peerCount = 2,
            roomCount = 3,
            unreadRoomCount = 1,
            pinnedRoomCount = 2,
            blockedPeerCount = 1,
            visibleMessageCount = 7,
            visibleFileCount = 1,
            courierQueueSize = 3,
            courierEnabled = true,
            courierRetentionMinutes = 15,
            courierMaxPacketsPerOrigin = 32,
            transportStatuses = listOf(
                TransportStatus("LAN", TransportState.Ready, "Advertising on local Wi-Fi")
            ),
            diagnosticEvents = listOf(
                DiagnosticEvent(1_000L, "router", "started with 2 transports"),
                DiagnosticEvent(2_000L, "guard", "invalid ttl for Chat from abc123")
            )
        )
    }
}
