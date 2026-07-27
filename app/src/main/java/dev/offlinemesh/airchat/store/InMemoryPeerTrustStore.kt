package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.TrustedPeer

class InMemoryPeerTrustStore : PeerTrustStore {
    private val peers = linkedMapOf<String, TrustedPeer>()

    override fun loadTrustedPeers(): Map<String, TrustedPeer> = peers.toMap()

    override fun trustPeer(peer: TrustedPeer) {
        peers[peer.peerId] = peer
    }

    override fun forgetPeer(peerId: String) {
        peers.remove(peerId)
    }

    override fun clear() {
        peers.clear()
    }
}
