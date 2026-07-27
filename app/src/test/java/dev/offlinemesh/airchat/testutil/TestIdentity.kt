package dev.offlinemesh.airchat.testutil

import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class TestIdentity(
    override val displayName: String
) : MeshIdentity {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    override val publicKeyEncoded: String = IdentityStore.encode(keyPair.public.encoded)
    override val peerId: String = IdentityStore.stablePeerId(publicKeyEncoded)

    override fun sign(bytes: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair.private)
        signature.update(bytes)
        return IdentityStore.encode(signature.sign())
    }

    override fun privateKey(): PrivateKey = keyPair.private
}
