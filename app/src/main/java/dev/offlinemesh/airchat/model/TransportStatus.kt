package dev.offlinemesh.airchat.model

data class TransportStatus(
    val name: String,
    val state: TransportState,
    val detail: String
)

enum class TransportState {
    Stopped,
    Starting,
    Ready,
    Degraded,
    Failed
}
