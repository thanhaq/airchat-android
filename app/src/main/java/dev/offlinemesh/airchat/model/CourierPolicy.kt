package dev.offlinemesh.airchat.model

import kotlinx.serialization.Serializable

@Serializable
data class CourierPolicy(
    val enabled: Boolean = true,
    val retentionMinutes: Int = DEFAULT_RETENTION_MINUTES
) {
    val retentionMillis: Long
        get() = retentionMinutes * 60L * 1_000L

    fun sanitized(): CourierPolicy {
        return copy(retentionMinutes = retentionMinutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES))
    }

    companion object {
        const val DEFAULT_RETENTION_MINUTES = 15
        const val MIN_RETENTION_MINUTES = 5
        const val MAX_RETENTION_MINUTES = 60
        val Default = CourierPolicy()
    }
}
