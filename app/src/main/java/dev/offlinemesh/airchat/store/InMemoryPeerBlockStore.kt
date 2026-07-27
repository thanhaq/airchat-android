package dev.offlinemesh.airchat.store

class InMemoryPeerBlockStore : PeerBlockStore {
    private val peers = linkedSetOf<String>()

    override fun loadBlockedPeers(): Set<String> = peers.toSet()

    override fun blockPeer(peerId: String) {
        peers += peerId
    }

    override fun unblockPeer(peerId: String) {
        peers -= peerId
    }

    override fun clear() {
        peers.clear()
    }
}
