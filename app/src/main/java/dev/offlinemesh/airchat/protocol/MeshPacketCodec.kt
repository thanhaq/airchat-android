package dev.offlinemesh.airchat.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MeshPacketCodec {
    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(packet: MeshPacket): String = json.encodeToString(packet)

    fun decode(raw: String): MeshPacket? =
        runCatching { json.decodeFromString<MeshPacket>(raw) }.getOrNull()

    fun signingBytes(packet: MeshPacket): ByteArray =
        encode(packet.copy(signature = null, ttl = 0, path = emptyList())).toByteArray(Charsets.UTF_8)

    inline fun <reified T> encodePayload(value: T): String = json.encodeToString(value)

    inline fun <reified T> decodePayload(raw: String): T? =
        runCatching { json.decodeFromString<T>(raw) }.getOrNull()
}
