package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import dev.offlinemesh.airchat.model.TrustedPeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SignedTrustBackup(
    val schema: String = TrustBackupCodec.SIGNED_SCHEMA,
    val payload: TrustBackupPayload,
    val signature: String
)

@Serializable
data class TrustBackupPayload(
    val schema: String = TrustBackupCodec.PAYLOAD_SCHEMA,
    val createdAt: Long,
    val exporterPeerId: String,
    val exporterDisplayName: String,
    val exporterPublicKey: String,
    val trustedPeers: List<TrustBackupPeer>
)

@Serializable
data class TrustBackupPeer(
    val peerId: String,
    val displayName: String,
    val publicKey: String,
    val trustedAt: Long
)

data class TrustBackupVerification(
    val isValid: Boolean,
    val reason: String? = null
)

object TrustBackupCodec {
    const val SIGNED_SCHEMA = "dev.offlinemesh.airchat.trust-backup.signed.v1"
    const val PAYLOAD_SCHEMA = "dev.offlinemesh.airchat.trust-backup.payload.v1"

    fun export(
        identity: MeshIdentity,
        trustedPeers: Collection<TrustedPeer>,
        createdAt: Long = System.currentTimeMillis()
    ): SignedTrustBackup {
        val payload = normalizePayload(
            TrustBackupPayload(
                createdAt = createdAt,
                exporterPeerId = identity.peerId,
                exporterDisplayName = identity.displayName,
                exporterPublicKey = identity.publicKeyEncoded,
                trustedPeers = trustedPeers.map { peer ->
                    TrustBackupPeer(
                        peerId = peer.peerId,
                        displayName = peer.displayName,
                        publicKey = peer.publicKey,
                        trustedAt = peer.trustedAt
                    )
                }
            )
        )
        return SignedTrustBackup(
            payload = payload,
            signature = identity.sign(signingBytes(payload))
        )
    }

    fun encode(backup: SignedTrustBackup): String =
        PrettyJson.encodeToString(backup)

    fun decode(raw: String): SignedTrustBackup? =
        runCatching { LenientJson.decodeFromString<SignedTrustBackup>(extractJson(raw)) }.getOrNull()

    fun verify(backup: SignedTrustBackup): TrustBackupVerification {
        if (backup.schema != SIGNED_SCHEMA) return invalid("Unsupported trust backup schema")
        if (backup.payload.schema != PAYLOAD_SCHEMA) return invalid("Unsupported trust backup payload schema")
        if (backup.payload != normalizePayload(backup.payload)) return invalid("Trust backup payload is not canonical")
        if (!canDecodePublicKey(backup.payload.exporterPublicKey)) {
            return invalid("Exporter public key is not valid")
        }
        if (IdentityStore.stablePeerId(backup.payload.exporterPublicKey) != backup.payload.exporterPeerId) {
            return invalid("Exporter peer id does not match the exporter public key")
        }
        val invalidPeer = backup.payload.trustedPeers.firstOrNull { peer ->
            !canDecodePublicKey(peer.publicKey) || IdentityStore.stablePeerId(peer.publicKey) != peer.peerId
        }
        if (invalidPeer != null) {
            return invalid("Trusted peer ${invalidPeer.peerId.take(12)} does not match its public key")
        }
        val signed = IdentityStore.verify(
            publicKeyEncoded = backup.payload.exporterPublicKey,
            bytes = signingBytes(backup.payload),
            signatureEncoded = backup.signature
        )
        return if (signed) TrustBackupVerification(isValid = true) else invalid("Trust backup signature mismatch")
    }

    fun verifiedTrustedPeers(backup: SignedTrustBackup): List<TrustedPeer>? {
        if (!verify(backup).isValid) return null
        return backup.payload.trustedPeers.map { peer ->
            TrustedPeer(
                peerId = peer.peerId,
                displayName = peer.displayName,
                publicKey = peer.publicKey,
                trustedAt = peer.trustedAt
            )
        }
    }

    fun formatShareText(backup: SignedTrustBackup): String = buildString {
        appendLine("AirChat signed trust backup")
        appendLine("Exporter: ${backup.payload.exporterDisplayName} / ${backup.payload.exporterPeerId}")
        appendLine("Trusted peers: ${backup.payload.trustedPeers.size}")
        appendLine("Created at: ${backup.payload.createdAt}")
        appendLine("Signature: ${backup.signature.take(SIGNATURE_PREVIEW_LENGTH)}")
        appendLine("Contains trusted peer public keys only. No messages, passphrases, or private keys are included.")
        appendLine()
        appendLine("```json")
        appendLine(encode(backup))
        appendLine("```")
    }.trimEnd()

    internal fun signingBytes(payload: TrustBackupPayload): ByteArray =
        CanonicalJson.encodeToString(payload).toByteArray(Charsets.UTF_8)

    private fun normalizePayload(payload: TrustBackupPayload): TrustBackupPayload =
        payload.copy(
            schema = PAYLOAD_SCHEMA,
            createdAt = payload.createdAt.coerceAtLeast(0L),
            exporterPeerId = payload.exporterPeerId.trim(),
            exporterDisplayName = payload.exporterDisplayName.sanitizedLabel(payload.exporterPeerId),
            exporterPublicKey = payload.exporterPublicKey.trim(),
            trustedPeers = payload.trustedPeers.normalizedPeers()
        )

    private fun List<TrustBackupPeer>.normalizedPeers(): List<TrustBackupPeer> =
        map { peer ->
            TrustBackupPeer(
                peerId = peer.peerId.trim(),
                displayName = peer.displayName.sanitizedLabel(peer.peerId),
                publicKey = peer.publicKey.trim(),
                trustedAt = peer.trustedAt.coerceAtLeast(0L)
            )
        }
            .filter { peer -> peer.peerId.isNotBlank() && peer.publicKey.isNotBlank() }
            .distinctBy { peer -> peer.peerId }
            .sortedBy { peer -> peer.peerId }

    private fun String.sanitizedLabel(fallback: String): String {
        val compact = trim().replace(Regex("\\s+"), " ").take(MAX_DISPLAY_NAME_LENGTH)
        return compact.ifBlank { fallback.trim().take(MAX_DISPLAY_NAME_LENGTH).ifBlank { "unknown" } }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf(JSON_FENCE)
        if (start < 0) return trimmed
        val contentStart = start + JSON_FENCE.length
        val end = trimmed.indexOf("```", startIndex = contentStart)
        return if (end < 0) trimmed.substring(contentStart).trim() else trimmed.substring(contentStart, end).trim()
    }

    private fun canDecodePublicKey(publicKey: String): Boolean =
        runCatching { IdentityStore.decodePublicKey(publicKey) }.isSuccess

    private fun invalid(reason: String): TrustBackupVerification =
        TrustBackupVerification(isValid = false, reason = reason)

    private val CanonicalJson = Json {
        encodeDefaults = true
    }
    private val PrettyJson = Json {
        encodeDefaults = true
        prettyPrint = true
    }
    private val LenientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val MAX_DISPLAY_NAME_LENGTH = 64
    private const val SIGNATURE_PREVIEW_LENGTH = 24
    private const val JSON_FENCE = "```json"
}
