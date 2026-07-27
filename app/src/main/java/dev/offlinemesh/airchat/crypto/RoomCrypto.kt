package dev.offlinemesh.airchat.crypto

import dev.offlinemesh.airchat.protocol.RoomEncryptedPayload
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class RoomKey(
    val channel: String,
    val bytes: ByteArray,
    val verificationCode: String,
    val strength: PassphraseStrength
)

object RoomCrypto {
    private const val VERSION = 1
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 180_000
    private const val NONCE_BYTES = 12
    private val secureRandom = SecureRandom()

    fun deriveRoomKey(channel: String, passphrase: String): RoomKey {
        val bytes = deriveKeyBytes(channel = channel, passphrase = passphrase)
        return RoomKey(
            channel = channel,
            bytes = bytes,
            verificationCode = verificationCode(channel = channel, keyBytes = bytes),
            strength = PassphraseStrengthMeter.estimate(passphrase)
        )
    }

    fun encrypt(
        channel: String,
        passphrase: String,
        packetId: String,
        plaintext: ByteArray
    ): RoomEncryptedPayload {
        val roomKey = deriveRoomKey(channel = channel, passphrase = passphrase)
        return try {
            encrypt(roomKey = roomKey, packetId = packetId, plaintext = plaintext)
        } finally {
            roomKey.bytes.fill(0)
        }
    }

    fun encrypt(
        roomKey: RoomKey,
        packetId: String,
        plaintext: ByteArray
    ): RoomEncryptedPayload {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(roomKey.bytes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(roomKey.channel, packetId))
        val ciphertext = cipher.doFinal(plaintext)
        return RoomEncryptedPayload(
            version = VERSION,
            nonce = encode(nonce),
            ciphertext = encode(ciphertext)
        )
    }

    fun decrypt(
        channel: String,
        passphrase: String,
        packetId: String,
        payload: RoomEncryptedPayload
    ): ByteArray? {
        val roomKey = deriveRoomKey(channel = channel, passphrase = passphrase)
        return try {
            decrypt(roomKey = roomKey, packetId = packetId, payload = payload)
        } finally {
            roomKey.bytes.fill(0)
        }
    }

    fun decrypt(
        roomKey: RoomKey,
        packetId: String,
        payload: RoomEncryptedPayload
    ): ByteArray? {
        if (payload.version != VERSION) return null
        return runCatching {
            val nonce = decode(payload.nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(roomKey.bytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(roomKey.channel, packetId))
            cipher.doFinal(decode(payload.ciphertext))
        }.getOrNull()
    }

    private fun deriveKeyBytes(channel: String, passphrase: String): ByteArray {
        val scopedSalt = "airchat-room-v1:$channel".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(passphrase.toCharArray(), scopedSalt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(channel: String, packetId: String): ByteArray =
        "airchat-room-v1:$channel:$packetId".toByteArray(Charsets.UTF_8)

    private fun verificationCode(channel: String, keyBytes: ByteArray): String {
        val input = "airchat-room-code-v1:$channel:".toByteArray(Charsets.UTF_8) + keyBytes
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return encodeHex(digest.copyOf(6)).chunked(4).joinToString("-")
    }

    private fun encodeHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xFF
            hex[index * 2] = HEX_CHARS[unsigned ushr 4]
            hex[index * 2 + 1] = HEX_CHARS[unsigned and 0x0F]
        }
        return String(hex)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
}
