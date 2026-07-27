package dev.offlinemesh.airchat.crypto

import java.security.MessageDigest

data class RoomInvitePayload(
    val channel: String,
    val channelDigest: String,
    val verificationCode: String
)

object VerificationPayload {
    fun safety(localPublicKey: String, remotePublicKey: String): String {
        return "AIRCHAT-SAFETY:${SafetyNumber.fingerprint(localPublicKey, remotePublicKey)}"
    }

    fun room(channel: String, verificationCode: String): String {
        return "AIRCHAT-ROOM:${digestPrefix(channel)}:${verificationCode.trim()}"
    }

    fun roomInvite(channel: String, verificationCode: String): String {
        val sanitizedChannel = sanitizeChannel(channel)
        return "AIRCHAT-ROOM-INVITE:1:$sanitizedChannel:${digestPrefix(sanitizedChannel)}:${verificationCode.trim()}"
    }

    fun parseRoomInvite(payload: String): RoomInvitePayload? {
        val parts = payload.trim().split(':')
        if (parts.size != ROOM_INVITE_PARTS) return null
        if (parts[0] != "AIRCHAT-ROOM-INVITE" || parts[1] != "1") return null
        val channel = sanitizeChannel(parts[2])
        if (channel != parts[2] || channel.isBlank()) return null
        val channelDigest = parts[3]
        val verificationCode = parts[4]
        if (channelDigest != digestPrefix(channel)) return null
        if (!verificationCode.matches(ROOM_CODE_PATTERN)) return null
        return RoomInvitePayload(
            channel = channel,
            channelDigest = channelDigest,
            verificationCode = verificationCode
        )
    }

    private fun digestPrefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
        return digest.copyOf(6).joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun sanitizeChannel(value: String): String {
        return value
            .trim()
            .removePrefix("#")
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { DEFAULT_CHANNEL }
            .take(MAX_CHANNEL_LENGTH)
    }

    private const val DEFAULT_CHANNEL = "lobby"
    private const val MAX_CHANNEL_LENGTH = 32
    private const val ROOM_INVITE_PARTS = 5
    private val ROOM_CODE_PATTERN = Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")
}
