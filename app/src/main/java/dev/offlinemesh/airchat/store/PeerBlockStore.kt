package dev.offlinemesh.airchat.store

interface PeerBlockStore {
    fun loadBlockedPeers(): Set<String>
    fun blockPeer(peerId: String)
    fun unblockPeer(peerId: String)
    fun clear()
}
