package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.TrustedPeer

interface PeerTrustStore {
    fun loadTrustedPeers(): Map<String, TrustedPeer>
    fun trustPeer(peer: TrustedPeer)
    fun forgetPeer(peerId: String)
    fun clear()
}
