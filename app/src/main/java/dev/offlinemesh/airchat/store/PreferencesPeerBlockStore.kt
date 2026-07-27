package dev.offlinemesh.airchat.store

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesPeerBlockStore(context: Context) : PeerBlockStore {
    private val prefs = context.getSharedPreferences("airchat_peer_blocks", Context.MODE_PRIVATE)
    private val cipher = AndroidKeyStoreCipher("airchat_peer_blocks_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadBlockedPeers(): Set<String> {
        val raw = prefs.getString(KEY_BLOCKED_PEERS, null) ?: return emptySet()
        val decoded = runCatching { cipher.decrypt(raw) }.getOrElse { raw }
        return runCatching { json.decodeFromString<List<String>>(decoded).filterValidPeerIds().toSet() }
            .getOrDefault(emptySet())
    }

    override fun blockPeer(peerId: String) {
        val peers = loadBlockedPeers() + peerId
        save(peers)
    }

    override fun unblockPeer(peerId: String) {
        val peers = loadBlockedPeers() - peerId
        save(peers)
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOCKED_PEERS).apply()
        runCatching { cipher.deleteKey() }
    }

    private fun save(peers: Set<String>) {
        val raw = json.encodeToString(peers.toList().filterValidPeerIds().sorted())
        val encrypted = runCatching { cipher.encrypt(raw) }.getOrNull() ?: return
        prefs.edit().putString(KEY_BLOCKED_PEERS, encrypted).apply()
    }

    private fun List<String>.filterValidPeerIds(): List<String> =
        map { it.trim() }
            .filter { it.matches(PEER_ID_PATTERN) }
            .take(MAX_BLOCKED_PEERS)

    private companion object {
        const val KEY_BLOCKED_PEERS = "blocked_peers"
        const val MAX_BLOCKED_PEERS = 512
        val PEER_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
    }
}
