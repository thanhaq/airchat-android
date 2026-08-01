package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.crypto.IdentityKeySecurity
import dev.offlinemesh.airchat.model.DiagnosticEvent
import dev.offlinemesh.airchat.model.TransportStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
    val powerMode: String,
    val batteryState: String,
    val peerCount: Int,
    val roomCount: Int,
    val unreadRoomCount: Int,
    val pinnedRoomCount: Int,
    val blockedPeerCount: Int,
    val visibleMessageCount: Int,
    val visibleFileCount: Int,
    val courierQueueSize: Int,
    val courierEnabled: Boolean,
    val courierRetentionMinutes: Int,
    val courierMaxPacketsPerOrigin: Int,
    val transportStatuses: List<TransportStatus>,
    val diagnosticEvents: List<DiagnosticEvent>
)

object DiagnosticsReportFormatter {
    const val PROTOCOL_VERSION = "airchat-mesh-v1"
    const val JSON_SCHEMA = "dev.offlinemesh.airchat.diagnostics.v1"

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
        appendLine("Power mode: ${snapshot.powerMode}")
        appendLine("Battery: ${snapshot.batteryState}")
        appendLine("Peers visible: ${snapshot.peerCount}")
        appendLine("Rooms visible: ${snapshot.roomCount}")
        appendLine("Rooms unread: ${snapshot.unreadRoomCount}")
        appendLine("Rooms pinned: ${snapshot.pinnedRoomCount}")
        appendLine("Peers blocked: ${snapshot.blockedPeerCount}")
        appendLine("Visible messages: ${snapshot.visibleMessageCount}")
        appendLine("Visible files: ${snapshot.visibleFileCount}")
        appendLine("Courier queue: ${snapshot.courierQueueSize}")
        appendLine("Courier relay: ${if (snapshot.courierEnabled) "on" else "off"}")
        appendLine("Courier retention: ${snapshot.courierRetentionMinutes}m")
        appendLine("Courier quota: ${snapshot.courierMaxPacketsPerOrigin} per origin")
        appendLine("Transports:")
        if (snapshot.transportStatuses.isEmpty()) {
            appendLine("- none")
        } else {
            snapshot.transportStatuses.sortedBy { it.name }.forEach { status ->
                appendLine("- ${status.name}: ${status.state.name} (${status.detail})")
            }
        }
        appendLine("Recent events:")
        if (snapshot.diagnosticEvents.isEmpty()) {
            appendLine("- none")
        } else {
            snapshot.diagnosticEvents.takeLast(MAX_EVENTS_IN_REPORT).forEach { event ->
                appendLine("- ${eventTime(event.createdAt)} ${event.category}: ${event.detail}")
            }
        }
    }.trimEnd()

    fun formatJson(snapshot: DiagnosticsSnapshot): String {
        val root = buildJsonObject {
            put("schema", JsonPrimitive(JSON_SCHEMA))
            putObject("app") {
                put("version", snapshot.appVersion)
            }
            putObject("protocol") {
                put("version", snapshot.protocolVersion)
            }
            putObject("device") {
                put("label", snapshot.device)
                put("android", snapshot.androidVersion)
            }
            putObject("peer") {
                put("id", snapshot.localPeerId)
                put("nickname", snapshot.nickname)
                put("label", "${snapshot.nickname} / ${snapshot.localPeerId}")
                put("identityKey", identityKeyLabel(snapshot.identityKeySecurity))
            }
            putObject("conversation") {
                put("mode", if (snapshot.directPeerName == null) "room" else "direct")
                put("label", conversationLabel(snapshot))
                put("channel", snapshot.channel)
                putNullable("directPeerName", snapshot.directPeerName)
            }
            putObject("privateRoom") {
                put("enabled", snapshot.privateRoomEnabled)
                put("label", privateRoomLabel(snapshot))
                putNullable("code", snapshot.privateRoomCode)
                putNullable("strength", snapshot.privateRoomStrength)
            }
            putObject("backgroundMesh") {
                put("enabled", snapshot.backgroundMeshEnabled)
                put("label", if (snapshot.backgroundMeshEnabled) "on" else "off")
            }
            putObject("power") {
                put("mode", snapshot.powerMode)
                put("battery", snapshot.batteryState)
            }
            putObject("counts") {
                put("peersVisible", snapshot.peerCount)
                put("roomsVisible", snapshot.roomCount)
                put("roomsUnread", snapshot.unreadRoomCount)
                put("roomsPinned", snapshot.pinnedRoomCount)
                put("peersBlocked", snapshot.blockedPeerCount)
                put("visibleMessages", snapshot.visibleMessageCount)
                put("visibleFiles", snapshot.visibleFileCount)
            }
            putObject("courier") {
                put("queueSize", snapshot.courierQueueSize)
                put("enabled", snapshot.courierEnabled)
                put("relay", if (snapshot.courierEnabled) "on" else "off")
                put("retentionMinutes", snapshot.courierRetentionMinutes)
                put("maxPacketsPerOrigin", snapshot.courierMaxPacketsPerOrigin)
                put("quota", "${snapshot.courierMaxPacketsPerOrigin} per origin")
            }
            put(
                "transports",
                buildJsonArray {
                    snapshot.transportStatuses.sortedBy { it.name }.forEach { status ->
                        add(
                            buildJsonObject {
                                put("name", status.name)
                                put("state", status.state.name)
                                put("detail", status.detail)
                                put("label", "${status.state.name} (${status.detail})")
                            }
                        )
                    }
                }
            )
            put(
                "recentEvents",
                buildJsonArray {
                    snapshot.diagnosticEvents.takeLast(MAX_EVENTS_IN_REPORT).forEach { event ->
                        val time = eventTime(event.createdAt)
                        add(
                            buildJsonObject {
                                put("createdAt", event.createdAt)
                                put("time", time)
                                put("category", event.category)
                                put("detail", event.detail)
                                put("label", "$time ${event.category}: ${event.detail}")
                            }
                        )
                    }
                }
            )
        }
        return PrettyJson.encodeToString(JsonObject.serializer(), root)
    }

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

    private fun eventTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))

    private fun JsonObjectBuilder.put(key: String, value: String) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.put(key: String, value: Int) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.put(key: String, value: Long) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.put(key: String, value: Boolean) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putObject(key: String, block: JsonObjectBuilder.() -> Unit) {
        put(key, buildJsonObject(block))
    }

    private val PrettyJson = Json {
        prettyPrint = true
    }

    private const val MAX_EVENTS_IN_REPORT = 12
}
