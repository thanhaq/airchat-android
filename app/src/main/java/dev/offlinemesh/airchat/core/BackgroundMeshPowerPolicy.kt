package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.MeshPowerMode
import dev.offlinemesh.airchat.model.MeshPowerPolicy

data class DevicePowerSnapshot(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val isBatterySaver: Boolean
)

data class BackgroundPowerStatus(
    val snapshot: DevicePowerSnapshot,
    val policy: MeshPowerPolicy
) {
    val mode: MeshPowerMode
        get() = policy.mode

    val batteryLabel: String
        get() {
            val percent = snapshot.batteryPercent?.let { "$it%" } ?: "unknown"
            val charging = if (snapshot.isCharging) "charging" else "unplugged"
            val saver = if (snapshot.isBatterySaver) "battery saver on" else "battery saver off"
            return "$percent / $charging / $saver"
        }

    val diagnosticsLabel: String
        get() = when (mode) {
            MeshPowerMode.Normal -> "normal"
            MeshPowerMode.Conserve -> "conserve"
            MeshPowerMode.Critical -> "critical"
        }

    val notificationText: String
        get() = when (mode) {
            MeshPowerMode.Normal -> "Listening for nearby peers on local Wi-Fi."
            MeshPowerMode.Conserve -> "Mesh running in conserve mode to reduce relay churn."
            MeshPowerMode.Critical -> "Mesh running in critical battery mode; courier storage is paused."
        }
}

object BackgroundMeshPowerPolicy {
    fun evaluate(snapshot: DevicePowerSnapshot): BackgroundPowerStatus {
        val percent = snapshot.batteryPercent
        val policy = when {
            !snapshot.isCharging && percent != null && percent <= CRITICAL_BATTERY_PERCENT -> MeshPowerPolicy.Critical
            !snapshot.isCharging && snapshot.isBatterySaver -> MeshPowerPolicy.Conserve
            !snapshot.isCharging && percent != null && percent <= LOW_BATTERY_PERCENT -> MeshPowerPolicy.Conserve
            else -> MeshPowerPolicy.Normal
        }
        return BackgroundPowerStatus(snapshot = snapshot, policy = policy)
    }

    private const val LOW_BATTERY_PERCENT = 20
    private const val CRITICAL_BATTERY_PERCENT = 10
}
