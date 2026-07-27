package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationPayloadTest {
    @Test
    fun safetyPayloadContainsFullFingerprint() {
        val fingerprint = SafetyNumber.fingerprint("key-a", "key-b")
        val payload = VerificationPayload.safety("key-a", "key-b")

        assertTrue(payload.startsWith("AIRCHAT-SAFETY:"))
        assertTrue(payload.endsWith(fingerprint))
    }

    @Test
    fun roomPayloadDoesNotExposeRoomNameOrPassphrase() {
        val payload = VerificationPayload.room("secret-room-name", "ABCD-1234-EF90")

        assertTrue(payload.startsWith("AIRCHAT-ROOM:"))
        assertTrue(payload.endsWith(":ABCD-1234-EF90"))
        assertFalse(payload.contains("secret-room-name"))
    }
}
