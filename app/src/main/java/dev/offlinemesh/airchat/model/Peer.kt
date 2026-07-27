package dev.offlinemesh.airchat.model

data class Peer(
    val id: String,
    val name: String,
    val transport: TransportKind,
    val publicKey: String? = null,
    val address: String? = null,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val connectionState: PeerConnectionState = PeerConnectionState.Discovered,
    val trustState: PeerTrustState = PeerTrustState.Unknown
)

enum class TransportKind {
    Lan,
    WifiDirect
}

enum class PeerConnectionState {
    Discovered,
    Connecting,
    Connected,
    Unreachable
}

enum class PeerTrustState {
    Unknown,
    Trusted,
    KeyChanged
}
