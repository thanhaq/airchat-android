package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.crypto.IdentityKeySecurity
import dev.offlinemesh.airchat.model.TransportStatus

data class DiagnosticsSnapshot(
    val appVersion: String,
    val protocolVersion: String,
    val device: String,
    val androidVersion: String,
    val localPeerId: String,
    val nickname: String,
    val identityKeySecurity: IdentityKeySecurity,
    val channel: String,
    val privateRoomEnabled: Boolean,
    val privateRoomCode: String?,
    val privateRoomStrength: String?,
    val directPeerName: String?,
    val backgroundMeshEnabled: Boolean,
    val peerCount: Int,
    val roomCount: Int,
    val unreadRoomCount: Int,
    val visibleMessageCount: Int,
    val visibleFileCount: Int,
    val courierQueueSize: Int,
    val transportStatuses: List<TransportStatus>
)

object DiagnosticsReportFormatter {
    const val PROTOCOL_VERSION = "airchat-mesh-v1"

    fun format(snapshot: DiagnosticsSnapshot): String = buildString {
        appendLine("AirChat diagnostics")
        appendLine("App: ${snapshot.appVersion}")
        appendLine("Protocol: ${snapshot.protocolVersion}")
        appendLine("Device: ${snapshot.device}")
        appendLine("Android: ${snapshot.androidVersion}")
        appendLine("Peer: ${snapshot.nickname} / ${snapshot.localPeerId}")
        appendLine("Identity key: ${identityKeyLabel(snapshot.identityKeySecurity)}")
        appendLine("Conversation: ${conversationLabel(snapshot)}")
        appendLine("Private room: ${privateRoomLabel(snapshot)}")
        appendLine("Background mesh: ${if (snapshot.backgroundMeshEnabled) "on" else "off"}")
        appendLine("Peers visible: ${snapshot.peerCount}")
        appendLine("Rooms visible: ${snapshot.roomCount}")
        appendLine("Rooms unread: ${snapshot.unreadRoomCount}")
        appendLine("Visible messages: ${snapshot.visibleMessageCount}")
        appendLine("Visible files: ${snapshot.visibleFileCount}")
        appendLine("Courier queue: ${snapshot.courierQueueSize}")
        appendLine("Transports:")
        if (snapshot.transportStatuses.isEmpty()) {
            appendLine("- none")
        } else {
            snapshot.transportStatuses.sortedBy { it.name }.forEach { status ->
                appendLine("- ${status.name}: ${status.state.name} (${status.detail})")
            }
        }
    }.trimEnd()

    fun identityKeyLabel(security: IdentityKeySecurity): String {
        return when (security) {
            IdentityKeySecurity.AndroidKeyStoreHardwareBacked -> "Android Keystore hardware-backed"
            IdentityKeySecurity.AndroidKeyStoreSoftwareBacked -> "Android Keystore software-backed"
            IdentityKeySecurity.AndroidKeyStoreUnknownBacking -> "Android Keystore backing unknown"
            IdentityKeySecurity.SoftwareFallback -> "App-private software fallback"
        }
    }

    private fun conversationLabel(snapshot: DiagnosticsSnapshot): String {
        return snapshot.directPeerName?.let { "DM with $it" } ?: "#${snapshot.channel}"
    }

    private fun privateRoomLabel(snapshot: DiagnosticsSnapshot): String {
        if (!snapshot.privateRoomEnabled) return "off"
        return listOfNotNull(
            "on",
            snapshot.privateRoomCode?.let { "code $it" },
            snapshot.privateRoomStrength?.let { "strength $it" }
        ).joinToString(" / ")
    }
}
