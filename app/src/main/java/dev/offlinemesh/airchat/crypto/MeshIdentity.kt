package dev.offlinemesh.airchat.crypto

import java.security.PrivateKey

interface MeshIdentity {
    val peerId: String
    val displayName: String
    val publicKeyEncoded: String

    fun sign(bytes: ByteArray): String
    fun privateKey(): PrivateKey
}
