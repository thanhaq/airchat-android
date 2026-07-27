package dev.offlinemesh.airchat.store

import android.content.Context
import dev.offlinemesh.airchat.model.TrustedPeer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesPeerTrustStore(context: Context) : PeerTrustStore {
    private val prefs = context.getSharedPreferences("airchat_peer_trust", Context.MODE_PRIVATE)
    private val cipher = AndroidKeyStoreCipher("airchat_peer_trust_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadTrustedPeers(): Map<String, TrustedPeer> {
        val raw = prefs.getString(KEY_TRUSTED_PEERS, null) ?: return emptyMap()
        val decoded = runCatching { cipher.decrypt(raw) }.getOrElse { raw }
        return runCatching {
            json.decodeFromString<List<TrustedPeer>>(decoded).associateBy { it.peerId }
        }.getOrDefault(emptyMap())
    }

    override fun trustPeer(peer: TrustedPeer) {
        val peers = loadTrustedPeers() + (peer.peerId to peer)
        save(peers.values.toList())
    }

    override fun forgetPeer(peerId: String) {
        val peers = loadTrustedPeers() - peerId
        save(peers.values.toList())
    }

    override fun clear() {
        prefs.edit().remove(KEY_TRUSTED_PEERS).apply()
        runCatching { cipher.deleteKey() }
    }

    private fun save(peers: List<TrustedPeer>) {
        val raw = json.encodeToString(peers)
        val encrypted = runCatching { cipher.encrypt(raw) }.getOrNull() ?: return
        prefs.edit().putString(KEY_TRUSTED_PEERS, encrypted).apply()
    }

    private companion object {
        const val KEY_TRUSTED_PEERS = "trusted_peers"
    }
}
