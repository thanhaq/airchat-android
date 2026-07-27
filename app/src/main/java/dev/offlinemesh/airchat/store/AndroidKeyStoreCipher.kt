package dev.offlinemesh.airchat.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeyStoreCipher(
    private val alias: String
) {
    fun encrypt(plaintext: String): String =
        String(encryptBytes(plaintext.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    fun decrypt(encoded: String): String =
        String(decryptBytes(encoded.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext)
        return "$PREFIX${encode(cipher.iv)}.${encode(ciphertext)}".toByteArray(Charsets.UTF_8)
    }

    fun decryptBytes(encoded: ByteArray): ByteArray {
        val encodedText = String(encoded, Charsets.UTF_8)
        if (!encodedText.startsWith(PREFIX)) return encoded
        val parts = encodedText.removePrefix(PREFIX).split(".")
        require(parts.size == 2) { "Invalid encrypted value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, decode(parts[0]))
        )
        return cipher.doFinal(decode(parts[1]))
    }

    fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "aksv1:"
        const val TAG_BITS = 128
    }
}
