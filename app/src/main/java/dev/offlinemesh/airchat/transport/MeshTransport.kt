package dev.offlinemesh.airchat.transport

import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.protocol.MeshPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MeshTransport {
    val name: String
    val status: StateFlow<TransportStatus>
    val peers: StateFlow<List<Peer>>
    val events: Flow<TransportEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun broadcast(packet: MeshPacket): Boolean
    suspend fun send(peerId: String, packet: MeshPacket): Boolean
}

sealed interface TransportEvent {
    data class PacketReceived(
        val transportName: String,
        val packet: MeshPacket,
        val remotePeer: Peer?
    ) : TransportEvent
}
