package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.TrustedPeer
import dev.offlinemesh.airchat.testutil.TestIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustBackupCodecTest {
    @Test
    fun exportsSignedBackupAndVerifies() {
        val alice = TestIdentity("Alice Field Phone")
        val bob = TestIdentity("Bob")
        val carol = TestIdentity("Carol")

        val backup = TrustBackupCodec.export(
            identity = alice,
            trustedPeers = listOf(
                trustedPeer(carol, trustedAt = 3_000L),
                trustedPeer(bob, trustedAt = 1_000L)
            ),
            createdAt = 9_000L
        )

        assertEquals(TrustBackupCodec.SIGNED_SCHEMA, backup.schema)
        assertEquals(alice.peerId, backup.payload.exporterPeerId)
        assertEquals("Alice Field Phone", backup.payload.exporterDisplayName)
        assertEquals(
            listOf(bob.peerId, carol.peerId).sorted(),
            backup.payload.trustedPeers.map { it.peerId }
        )
        assertTrue(TrustBackupCodec.verify(backup).isValid)

        val decoded = TrustBackupCodec.decode(TrustBackupCodec.encode(backup))
        assertNotNull(decoded)
        assertTrue(TrustBackupCodec.verify(decoded!!).isValid)
    }

    @Test
    fun shareTextContainsVerifiableJsonWithoutPrivateMaterial() {
        val alice = TestIdentity("Alice")
        val bob = TestIdentity("Bob")
        val backup = TrustBackupCodec.export(
            identity = alice,
            trustedPeers = listOf(trustedPeer(bob)),
            createdAt = 1L
        )

        val shareText = TrustBackupCodec.formatShareText(backup)

        assertTrue(shareText.contains("AirChat signed trust backup"))
        assertTrue(shareText.contains("Trusted peers: 1"))
        assertTrue(shareText.contains("```json"))
        assertFalse(shareText.contains("BEGIN PRIVATE KEY"))
        assertFalse(shareText.contains("PRIVATE KEY-----"))

        val decoded = TrustBackupCodec.decode(shareText)
        assertNotNull(decoded)
        assertTrue(TrustBackupCodec.verify(decoded!!).isValid)
        assertEquals(listOf(trustedPeer(bob)), TrustBackupCodec.verifiedTrustedPeers(decoded))
    }

    @Test
    fun detectsTamperedTrustedPeerPayload() {
        val alice = TestIdentity("Alice")
        val bob = TestIdentity("Bob")
        val backup = TrustBackupCodec.export(
            identity = alice,
            trustedPeers = listOf(trustedPeer(bob)),
            createdAt = 1L
        )

        val tampered = backup.copy(
            payload = backup.payload.copy(
                trustedPeers = backup.payload.trustedPeers.map { peer ->
                    peer.copy(displayName = "Mallory")
                }
            )
        )

        val verification = TrustBackupCodec.verify(tampered)

        assertFalse(verification.isValid)
        assertEquals("Trust backup signature mismatch", verification.reason)
        assertEquals(null, TrustBackupCodec.verifiedTrustedPeers(tampered))
    }

    @Test
    fun rejectsExporterPeerIdMismatch() {
        val alice = TestIdentity("Alice")
        val backup = TrustBackupCodec.export(
            identity = alice,
            trustedPeers = emptyList(),
            createdAt = 1L
        )

        val tampered = backup.copy(
            payload = backup.payload.copy(exporterPeerId = "not-${alice.peerId}")
        )

        val verification = TrustBackupCodec.verify(tampered)

        assertFalse(verification.isValid)
        assertEquals("Exporter peer id does not match the exporter public key", verification.reason)
    }

    private fun trustedPeer(identity: TestIdentity, trustedAt: Long = 1_000L): TrustedPeer =
        TrustedPeer(
            peerId = identity.peerId,
            displayName = identity.displayName,
            publicKey = identity.publicKeyEncoded,
            trustedAt = trustedAt
        )
}
