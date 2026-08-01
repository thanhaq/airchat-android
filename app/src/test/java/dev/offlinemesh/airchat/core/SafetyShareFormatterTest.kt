package dev.offlinemesh.airchat.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyShareFormatterTest {
    @Test
    fun formatsPeerSafetyCardWithoutRawPublicKeys() {
        val report = SafetyShareFormatter.format(
            peerName = "Bob Field Phone",
            peerId = "peer-1234567890abcdef",
            safetyNumber = "1234-ABCD-5678",
            safetyPayload = "AIRCHAT-SAFETY:FINGERPRINT"
        )

        assertTrue(report.contains("AirChat safety card"))
        assertTrue(report.contains("Peer: Bob Field Phone"))
        assertTrue(report.contains("Peer id: peer-1234567890abcdef"))
        assertTrue(report.contains("Safety number: 1234-ABCD-5678"))
        assertTrue(report.contains("Payload: AIRCHAT-SAFETY:FINGERPRINT"))
        assertFalse(report.contains("PUBLIC KEY"))
        assertFalse(report.contains("PRIVATE KEY"))
    }

    @Test
    fun boundsLongPeerLabels() {
        val report = SafetyShareFormatter.format(
            peerName = "A".repeat(80),
            peerId = "B".repeat(80),
            safetyNumber = "SAFE",
            safetyPayload = "AIRCHAT-SAFETY:SAFE"
        )

        assertTrue(report.contains("Peer: ${"A".repeat(48)}"))
        assertFalse(report.contains("Peer: ${"A".repeat(49)}"))
        assertTrue(report.contains("Peer id: ${"B".repeat(24)}"))
        assertFalse(report.contains("Peer id: ${"B".repeat(25)}"))
    }
}
