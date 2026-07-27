package dev.offlinemesh.airchat.protocol

class PacketGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPayloadBytes: Int = 64 * 1024,
    private val maxTtl: Int = 7,
    private val maxPathLength: Int = 12,
    private val maxPastAgeMs: Long = 24L * 60L * 60L * 1_000L,
    private val maxFutureSkewMs: Long = 5L * 60L * 1_000L,
    private val perOriginWindowMs: Long = 10_000L,
    private val maxPacketsPerWindow: Int = 80
) {
    private val originWindows = linkedMapOf<String, MutableList<Long>>()

    @Synchronized
    fun inspect(packet: MeshPacket): PacketGuardDecision {
        val now = clock()
        if (packet.ttl !in 0..maxTtl) {
            return PacketGuardDecision.Rejected("invalid ttl")
        }
        if (packet.path.size > maxPathLength) {
            return PacketGuardDecision.Rejected("path too long")
        }
        if (packet.createdAt < now - maxPastAgeMs) {
            return PacketGuardDecision.Rejected("packet too old")
        }
        if (packet.createdAt > now + maxFutureSkewMs) {
            return PacketGuardDecision.Rejected("packet from the future")
        }
        if (packet.payload.toByteArray(Charsets.UTF_8).size > maxPayloadBytes) {
            return PacketGuardDecision.Rejected("payload too large")
        }

        val window = originWindows.getOrPut(packet.originId) { mutableListOf() }
        window.removeAll { it < now - perOriginWindowMs }
        if (window.size >= maxPacketsPerWindow) {
            return PacketGuardDecision.RateLimited
        }
        window += now
        pruneOriginWindows(now)
        return PacketGuardDecision.Accepted
    }

    @Synchronized
    fun clear() {
        originWindows.clear()
    }

    private fun pruneOriginWindows(now: Long) {
        val iterator = originWindows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it < now - perOriginWindowMs }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }
}

sealed interface PacketGuardDecision {
    data object Accepted : PacketGuardDecision
    data object RateLimited : PacketGuardDecision
    data class Rejected(val reason: String) : PacketGuardDecision
}
