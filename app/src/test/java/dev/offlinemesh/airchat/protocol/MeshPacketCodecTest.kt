package dev.offlinemesh.airchat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshPacketCodecTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val packet = MeshPacket(
            id = "packet-1",
            type = PacketType.Chat,
            originId = "peer-a",
            originName = "alice",
            originPublicKey = "public-key",
            createdAt = 123L,
            ttl = 7,
            channel = "lobby",
            payload = "hello",
            signature = "signature"
        )

        val decoded = MeshPacketCodec.decode(MeshPacketCodec.encode(packet))

        assertNotNull(decoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun signingBytesIgnoreSignature() {
        val first = samplePacket(signature = "a")
        val second = samplePacket(signature = "b")

        assertEquals(
            String(MeshPacketCodec.signingBytes(first)),
            String(MeshPacketCodec.signingBytes(second))
        )
    }

    @Test
    fun signingBytesIgnoreRelayFields() {
        val first = samplePacket(signature = "a", ttl = 7, path = emptyList())
        val second = samplePacket(signature = "a", ttl = 4, path = listOf("relay-a", "relay-b"))

        assertEquals(
            String(MeshPacketCodec.signingBytes(first)),
            String(MeshPacketCodec.signingBytes(second))
        )
    }

    private fun samplePacket(
        signature: String,
        ttl: Int = 7,
        path: List<String> = emptyList()
    ) = MeshPacket(
        id = "packet-1",
        type = PacketType.Chat,
        originId = "peer-a",
        originName = "alice",
        originPublicKey = "public-key",
        createdAt = 123L,
        ttl = ttl,
        channel = "lobby",
        payload = "hello",
        signature = signature,
        path = path
    )
}
