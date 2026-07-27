package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoomCryptoTest {
    @Test
    fun decryptsWithMatchingChannelAndPassphrase() {
        val encrypted = RoomCrypto.encrypt(
            channel = "field_ops",
            passphrase = "shared passphrase",
            packetId = "packet-1",
            plaintext = "meet at checkpoint".toByteArray()
        )

        val decrypted = RoomCrypto.decrypt(
            channel = "field_ops",
            passphrase = "shared passphrase",
            packetId = "packet-1",
            payload = encrypted
        )

        assertEquals("meet at checkpoint", String(decrypted ?: ByteArray(0)))
    }

    @Test
    fun bindsCiphertextToChannelAndPassphrase() {
        val encrypted = RoomCrypto.encrypt(
            channel = "field_ops",
            passphrase = "shared passphrase",
            packetId = "packet-1",
            plaintext = "private room text".toByteArray()
        )

        assertNull(
            RoomCrypto.decrypt(
                channel = "other_room",
                passphrase = "shared passphrase",
                packetId = "packet-1",
                payload = encrypted
            )
        )
        assertNull(
            RoomCrypto.decrypt(
                channel = "field_ops",
                passphrase = "wrong passphrase",
                packetId = "packet-1",
                payload = encrypted
            )
        )
        assertNotEquals("private room text", encrypted.ciphertext)
    }
}
