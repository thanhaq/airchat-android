package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.crypto.IdentityKeySecurity
import dev.offlinemesh.airchat.model.TransportState
import dev.offlinemesh.airchat.model.TransportStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportFormatterTest {
    @Test
    fun formatsReadableDiagnosticsReport() {
        val report = DiagnosticsReportFormatter.format(
            DiagnosticsSnapshot(
                appVersion = "0.1.0-debug",
                protocolVersion = DiagnosticsReportFormatter.PROTOCOL_VERSION,
                device = "Example Phone",
                androidVersion = "15 / API 35",
                localPeerId = "peer123",
                nickname = "alice",
                identityKeySecurity = IdentityKeySecurity.AndroidKeyStoreHardwareBacked,
                channel = "lobby",
                privateRoomEnabled = true,
                directPeerName = null,
                backgroundMeshEnabled = true,
                peerCount = 2,
                visibleMessageCount = 7,
                visibleFileCount = 1,
                courierQueueSize = 3,
                transportStatuses = listOf(
                    TransportStatus("LAN", TransportState.Ready, "Advertising on local Wi-Fi")
                )
            )
        )

        assertTrue(report.contains("AirChat diagnostics"))
        assertTrue(report.contains("Identity key: Android Keystore hardware-backed"))
        assertTrue(report.contains("Conversation: #lobby"))
        assertTrue(report.contains("Private room: on"))
        assertTrue(report.contains("Courier queue: 3"))
        assertTrue(report.contains("- LAN: Ready (Advertising on local Wi-Fi)"))
    }
}
