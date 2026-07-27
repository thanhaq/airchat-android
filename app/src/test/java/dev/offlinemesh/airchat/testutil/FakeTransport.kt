package dev.offlinemesh.airchat.testutil

import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.TransportState
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.transport.MeshTransport
import dev.offlinemesh.airchat.transport.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTransport(
    override val name: String = "fake"
) : MeshTransport {
    private val statusState = MutableStateFlow(
        TransportStatus(name, TransportState.Stopped, "fake stopped")
    )
    private val peerState = MutableStateFlow<List<Peer>>(emptyList())
    private val eventFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    private val directPeers = mutableSetOf<String>()

    val broadcastedPackets = mutableListOf<MeshPacket>()
    val sentPackets = mutableListOf<Pair<String, MeshPacket>>()
    var broadcastSucceeds = true
    var sendSucceeds = true

    override val status: StateFlow<TransportStatus> = statusState.asStateFlow()
    override val peers: StateFlow<List<Peer>> = peerState.asStateFlow()
    override val events: Flow<TransportEvent> = eventFlow.asSharedFlow()

    override suspend fun start() {
        statusState.value = TransportStatus(name, TransportState.Ready, "fake ready")
    }

    override suspend fun stop() {
        statusState.value = TransportStatus(name, TransportState.Stopped, "fake stopped")
    }

    override suspend fun broadcast(packet: MeshPacket): Boolean {
        broadcastedPackets += packet
        return broadcastSucceeds
    }

    override suspend fun send(peerId: String, packet: MeshPacket): Boolean {
        sentPackets += peerId to packet
        return sendSucceeds && peerId in directPeers
    }

    suspend fun emitPacket(packet: MeshPacket, remotePeer: Peer?) {
        eventFlow.emit(TransportEvent.PacketReceived(name, packet, remotePeer))
    }

    fun publishPeers(peers: List<Peer>) {
        directPeers.clear()
        directPeers += peers.map { it.id }
        peerState.value = peers
    }
}
