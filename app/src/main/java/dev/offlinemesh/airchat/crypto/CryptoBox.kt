package dev.offlinemesh.airchat.crypto

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ephemeralPublicKey: String,
    val nonce: String,
    val ciphertext: String
)

class CryptoBox {
    private val secureRandom = SecureRandom()

    fun encryptFor(recipientPublicKey: PublicKey, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): EncryptedPayload {
        val ephemeral = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        }.generateKeyPair()

        val sharedSecret = agree(ephemeral.private, recipientPublicKey)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val key = deriveKey(
            sharedSecret = sharedSecret,
            salt = ephemeral.public.encoded + recipientPublicKey.encoded,
            info = "AirChat P256 AES-GCM v1".toByteArray()
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedPayload(
            ephemeralPublicKey = encode(ephemeral.public.encoded),
            nonce = encode(nonce),
            ciphertext = encode(ciphertext)
        )
    }

    fun decryptFor(
        recipientPrivateKey: PrivateKey,
        recipientPublicKey: PublicKey,
        senderEphemeralPublicKey: PublicKey,
        encryptedPayload: EncryptedPayload,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        val sharedSecret = agree(recipientPrivateKey, senderEphemeralPublicKey)
        val key = deriveKey(
            sharedSecret = sharedSecret,
            salt = senderEphemeralPublicKey.encoded + recipientPublicKey.encoded,
            info = "AirChat P256 AES-GCM v1".toByteArray()
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, decode(encryptedPayload.nonce))
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(decode(encryptedPayload.ciphertext))
    }

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun deriveKey(sharedSecret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val pseudoRandomKey = hmac(salt, sharedSecret)
        val block = hmac(pseudoRandomKey, info + byteArrayOf(1))
        return block.copyOf(32)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

}
