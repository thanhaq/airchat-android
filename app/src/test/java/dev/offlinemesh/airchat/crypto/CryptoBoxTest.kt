package dev.offlinemesh.airchat.crypto

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CryptoBoxTest {
    @Test
    fun encryptDecryptRoundTrip() {
        val recipient = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val box = CryptoBox()
        val aad = "packet-1".toByteArray()
        val plaintext = "private hello".toByteArray()

        val encrypted = box.encryptFor(recipient.public, plaintext, aad)
        val ephemeralPublic = IdentityStore.decodePublicKey(encrypted.ephemeralPublicKey)
        val decrypted = box.decryptFor(
            recipientPrivateKey = recipient.private,
            recipientPublicKey = recipient.public,
            senderEphemeralPublicKey = ephemeralPublic,
            encryptedPayload = encrypted,
            aad = aad
        )

        assertArrayEquals(plaintext, decrypted)
    }
}
