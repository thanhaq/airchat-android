package dev.offlinemesh.airchat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketGuardTest {
    private var now = 1_000_000L

    @Test
    fun acceptsReasonablePacket() {
        val guard = PacketGuard(clock = { now })

        assertEquals(PacketGuardDecision.Accepted, guard.inspect(packet()))
    }

    @Test
    fun rejectsInvalidTtlAndLongPaths() {
        val guard = PacketGuard(clock = { now })

        assertTrue(guard.inspect(packet(ttl = 99)) is PacketGuardDecision.Rejected)
        assertTrue(guard.inspect(packet(path = (1..20).map { "relay-$it" })) is PacketGuardDecision.Rejected)
    }

    @Test
    fun rejectsStaleFutureAndLargePayloads() {
        val guard = PacketGuard(clock = { now }, maxPayloadBytes = 8)

        assertTrue(guard.inspect(packet(createdAt = now - 25L * 60L * 60L * 1_000L)) is PacketGuardDecision.Rejected)
        assertTrue(guard.inspect(packet(createdAt = now + 6L * 60L * 1_000L)) is PacketGuardDecision.Rejected)
        assertTrue(guard.inspect(packet(payload = "this payload is too big")) is PacketGuardDecision.Rejected)
    }

    @Test
    fun rateLimitsNoisyOriginsInsideWindow() {
        val guard = PacketGuard(
            clock = { now },
            perOriginWindowMs = 1_000L,
            maxPacketsPerWindow = 2
        )

        assertEquals(PacketGuardDecision.Accepted, guard.inspect(packet(id = "one")))
        assertEquals(PacketGuardDecision.Accepted, guard.inspect(packet(id = "two")))
        assertEquals(PacketGuardDecision.RateLimited, guard.inspect(packet(id = "three")))
        now += 1_001L
        assertEquals(PacketGuardDecision.Accepted, guard.inspect(packet(id = "four")))
    }

    private fun packet(
        id: String = "packet-1",
        ttl: Int = 7,
        path: List<String> = emptyList(),
        createdAt: Long = now,
        payload: String = "hello"
    ) = MeshPacket(
        id = id,
        type = PacketType.Chat,
        originId = "origin",
        originName = "alice",
        originPublicKey = "public",
        createdAt = createdAt,
        ttl = ttl,
        channel = "lobby",
        payload = payload,
        path = path
    )
}
