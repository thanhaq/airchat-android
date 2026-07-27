package dev.offlinemesh.airchat.crypto

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import javax.crypto.KeyAgreement
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IdentityStore(context: Context) : MeshIdentity {
    private val prefs = context.getSharedPreferences("airchat_identity", Context.MODE_PRIVATE)
    private var keyPair: KeyPair = loadOrCreateKeyPair()
    private var keySecurity: IdentityKeySecurity = describeKey(keyPair.private)

    override val publicKeyEncoded: String
        get() = encode(keyPair.public.encoded)

    override val displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
            ?: "peer-${peerId.takeLast(4)}".also { prefs.edit().putString(KEY_DISPLAY_NAME, it).apply() }

    override val peerId: String
        get() = stablePeerId(publicKeyEncoded)

    val identityKeySecurity: IdentityKeySecurity
        get() = keySecurity

    override fun sign(bytes: ByteArray): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(bytes)
        return encode(signature.sign())
    }

    override fun privateKey(): PrivateKey = keyPair.private

    fun wipeFromDisk() {
        prefs.edit().clear().apply()
        runCatching {
            androidKeyStore().deleteEntry(KEYSTORE_ALIAS)
        }
        keyPair = loadOrCreateKeyPair()
        keySecurity = describeKey(keyPair.private)
    }

    companion object {
        private const val KEY_PRIVATE = "private_key_pkcs8"
        private const val KEY_PUBLIC = "public_key_x509"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "airchat_identity_v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun decodePublicKey(encoded: String): PublicKey {
            val bytes = decode(encoded)
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }

        fun verify(publicKeyEncoded: String, bytes: ByteArray, signatureEncoded: String): Boolean {
            return runCatching {
                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
                verifier.initVerify(decodePublicKey(publicKeyEncoded))
                verifier.update(bytes)
                verifier.verify(decode(signatureEncoded))
            }.getOrDefault(false)
        }

        fun stablePeerId(publicKeyEncoded: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("airchat-peer-id-v1".toByteArray(), "HmacSHA256"))
            val digest = mac.doFinal(publicKeyEncoded.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
        }

        fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decode(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        loadSoftwareKeyPair()?.let { return it }
        loadAndroidKeyStoreKeyPair()?.let { return it }
        createAndroidKeyStoreKeyPair()?.let { return it }
        return createSoftwareKeyPair()
    }

    private fun loadSoftwareKeyPair(): KeyPair? {
        val privateEncoded = prefs.getString(KEY_PRIVATE, null) ?: return null
        val publicEncoded = prefs.getString(KEY_PUBLIC, null) ?: return null
        return runCatching { decodeKeyPair(privateEncoded, publicEncoded) }
            .getOrNull()
            ?.takeIf(::canSignAndAgree)
    }

    private fun loadAndroidKeyStoreKeyPair(): KeyPair? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val keyStore = androidKeyStore()
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) return@runCatching null
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return@runCatching null
            KeyPair(entry.certificate.publicKey, entry.privateKey)
        }.getOrNull()?.takeIf(::canSignAndAgree)
    }

    private fun createAndroidKeyStoreKeyPair(): KeyPair? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return createAndroidKeyStoreKeyPairApi31()
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun createAndroidKeyStoreKeyPairApi31(): KeyPair? {
        return runCatching {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE
            )
            val purposes = KeyProperties.PURPOSE_SIGN or
                KeyProperties.PURPOSE_VERIFY or
                KeyProperties.PURPOSE_AGREE_KEY
            val spec = KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, purposes)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }.getOrNull()?.takeIf(::canSignAndAgree)
    }

    private fun createSoftwareKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val generated = generator.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, encode(generated.private.encoded))
            .putString(KEY_PUBLIC, encode(generated.public.encoded))
            .apply()
        return generated
    }

    private fun decodeKeyPair(privateEncoded: String, publicEncoded: String): KeyPair {
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(decode(privateEncoded)))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(decode(publicEncoded)))
        return KeyPair(publicKey, privateKey)
    }

    private fun canSignAndAgree(candidate: KeyPair): Boolean {
        return runCatching {
            val challenge = "airchat-identity-self-test".toByteArray()
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(candidate.private)
            signer.update(challenge)
            val encodedSignature = signer.sign()

            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(candidate.public)
            verifier.update(challenge)
            if (!verifier.verify(encodedSignature)) return false

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(candidate.private)
            agreement.doPhase(candidate.public, true)
            agreement.generateSecret().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun describeKey(privateKey: PrivateKey): IdentityKeySecurity {
        if (privateKey.format != null) {
            return IdentityKeySecurity.SoftwareFallback
        }
        return runCatching {
            val keyFactory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
            val keyInfo = keyFactory.getKeySpec(privateKey, KeyInfo::class.java)
            if (isHardwareBacked(keyInfo)) {
                IdentityKeySecurity.AndroidKeyStoreHardwareBacked
            } else {
                IdentityKeySecurity.AndroidKeyStoreSoftwareBacked
            }
        }.getOrDefault(IdentityKeySecurity.AndroidKeyStoreUnknownBacking)
    }

    private fun isHardwareBacked(keyInfo: KeyInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        }
    }

    private fun androidKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}

enum class IdentityKeySecurity {
    AndroidKeyStoreHardwareBacked,
    AndroidKeyStoreSoftwareBacked,
    AndroidKeyStoreUnknownBacking,
    SoftwareFallback
}
