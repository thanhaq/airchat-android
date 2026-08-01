package dev.offlinemesh.airchat.model

data class MeshPowerPolicy(
    val mode: MeshPowerMode,
    val maxRelayTtl: Int,
    val courierFlushIntervalMs: Long,
    val storeCourierPackets: Boolean
) {
    fun sanitized(): MeshPowerPolicy {
        return copy(
            maxRelayTtl = maxRelayTtl.coerceIn(MIN_RELAY_TTL, MAX_RELAY_TTL),
            courierFlushIntervalMs = courierFlushIntervalMs.coerceAtLeast(0L)
        )
    }

    companion object {
        const val MIN_RELAY_TTL = 1
        const val MAX_RELAY_TTL = 7

        val Normal = MeshPowerPolicy(
            mode = MeshPowerMode.Normal,
            maxRelayTtl = MAX_RELAY_TTL,
            courierFlushIntervalMs = 0L,
            storeCourierPackets = true
        )

        val Conserve = MeshPowerPolicy(
            mode = MeshPowerMode.Conserve,
            maxRelayTtl = 2,
            courierFlushIntervalMs = 60_000L,
            storeCourierPackets = true
        )

        val Critical = MeshPowerPolicy(
            mode = MeshPowerMode.Critical,
            maxRelayTtl = 1,
            courierFlushIntervalMs = 5L * 60_000L,
            storeCourierPackets = false
        )
    }
}

enum class MeshPowerMode {
    Normal,
    Conserve,
    Critical
}
