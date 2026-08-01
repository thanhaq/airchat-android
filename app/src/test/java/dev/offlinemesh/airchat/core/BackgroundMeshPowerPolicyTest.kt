package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.MeshPowerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMeshPowerPolicyTest {
    @Test
    fun normalModeWhenChargingEvenWithLowBattery() {
        val status = BackgroundMeshPowerPolicy.evaluate(
            DevicePowerSnapshot(
                batteryPercent = 9,
                isCharging = true,
                isBatterySaver = true
            )
        )

        assertEquals(MeshPowerMode.Normal, status.mode)
        assertEquals("9% / charging / battery saver on", status.batteryLabel)
    }

    @Test
    fun conserveModeWhenUnpluggedAndBatterySaverIsEnabled() {
        val status = BackgroundMeshPowerPolicy.evaluate(
            DevicePowerSnapshot(
                batteryPercent = 80,
                isCharging = false,
                isBatterySaver = true
            )
        )

        assertEquals(MeshPowerMode.Conserve, status.mode)
        assertEquals(2, status.policy.maxRelayTtl)
        assertTrue(status.policy.storeCourierPackets)
    }

    @Test
    fun criticalModePausesCourierStorageAtVeryLowBattery() {
        val status = BackgroundMeshPowerPolicy.evaluate(
            DevicePowerSnapshot(
                batteryPercent = 10,
                isCharging = false,
                isBatterySaver = false
            )
        )

        assertEquals(MeshPowerMode.Critical, status.mode)
        assertEquals(1, status.policy.maxRelayTtl)
        assertTrue(!status.policy.storeCourierPackets)
    }
}
