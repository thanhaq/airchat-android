package dev.offlinemesh.airchat.crypto

import java.security.MessageDigest

object VerificationPayload {
    fun safety(localPublicKey: String, remotePublicKey: String): String {
        return "AIRCHAT-SAFETY:${SafetyNumber.fingerprint(localPublicKey, remotePublicKey)}"
    }

    fun room(channel: String, verificationCode: String): String {
        return "AIRCHAT-ROOM:${digestPrefix(channel)}:${verificationCode.trim()}"
    }

    private fun digestPrefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
        return digest.copyOf(6).joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
}
