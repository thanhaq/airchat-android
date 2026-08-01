package dev.offlinemesh.airchat.model

import kotlinx.serialization.Serializable

@Serializable
data class CourierPolicy(
    val enabled: Boolean = true,
    val retentionMinutes: Int = DEFAULT_RETENTION_MINUTES,
    val maxPacketsPerOrigin: Int = DEFAULT_MAX_PACKETS_PER_ORIGIN
) {
    val retentionMillis: Long
        get() = retentionMinutes * 60L * 1_000L

    fun sanitized(): CourierPolicy {
        return copy(
            retentionMinutes = retentionMinutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES),
            maxPacketsPerOrigin = maxPacketsPerOrigin.coerceIn(
                MIN_PACKETS_PER_ORIGIN,
                MAX_PACKETS_PER_ORIGIN
            )
        )
    }

    companion object {
        const val DEFAULT_RETENTION_MINUTES = 15
        const val DEFAULT_MAX_PACKETS_PER_ORIGIN = 32
        const val MIN_RETENTION_MINUTES = 5
        const val MAX_RETENTION_MINUTES = 60
        const val MIN_PACKETS_PER_ORIGIN = 1
        const val MAX_PACKETS_PER_ORIGIN = 128
        val Default = CourierPolicy()
    }
}
