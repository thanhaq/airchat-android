package dev.offlinemesh.airchat.model

import kotlinx.serialization.Serializable

@Serializable
data class TrustedPeer(
    val peerId: String,
    val displayName: String,
    val publicKey: String,
    val trustedAt: Long
)
