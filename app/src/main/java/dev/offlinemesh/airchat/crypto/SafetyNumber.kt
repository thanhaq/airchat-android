package dev.offlinemesh.airchat.crypto

import java.security.MessageDigest

object SafetyNumber {
    fun shortCode(localPublicKey: String, remotePublicKey: String): String {
        return fingerprint(localPublicKey, remotePublicKey)
            .chunked(4)
            .take(3)
            .joinToString(" ")
    }

    fun fingerprint(localPublicKey: String, remotePublicKey: String): String {
        val ordered = listOf(localPublicKey, remotePublicKey).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${ordered[0]}:${ordered[1]}".toByteArray(Charsets.UTF_8)
        )
        return digest.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
}
