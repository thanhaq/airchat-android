package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun roomInviteCarriesRoomMetadataWithoutPassphrase() {
        val payload = VerificationPayload.roomInvite("field_ops", "ABCD-1234-EF90")
        val parsed = VerificationPayload.parseRoomInvite(payload)

        assertTrue(payload.startsWith("AIRCHAT-ROOM-INVITE:1:field_ops:"))
        assertFalse(payload.contains("shared passphrase"))
        assertEquals("field_ops", parsed?.channel)
        assertEquals("ABCD-1234-EF90", parsed?.verificationCode)
    }

    @Test
    fun roomInviteRejectsTamperedDigestOrCode() {
        val payload = VerificationPayload.roomInvite("field_ops", "ABCD-1234-EF90")
        val tamperedDigest = payload.replace(":ABCD-1234-EF90", ":DEAD-1234-EF90")
            .replace(":field_ops:", ":other_ops:")
        val tamperedCode = payload.replace("ABCD-1234-EF90", "not-a-code")

        assertNull(VerificationPayload.parseRoomInvite(tamperedDigest))
        assertNull(VerificationPayload.parseRoomInvite(tamperedCode))
    }

    @Test
    fun roomInvitePayloadFitsQrEncoderLimit() {
        val longestRoom = "abcdefghijklmnopqrstuvwxyz012345"
        val payload = VerificationPayload.roomInvite(longestRoom, "ABCD-1234-EF90")

        assertEquals(longestRoom.take(32), VerificationPayload.parseRoomInvite(payload)?.channel)
        assertTrue(QrCodeEncoder.encodeText(payload).size > 0)
    }
}
