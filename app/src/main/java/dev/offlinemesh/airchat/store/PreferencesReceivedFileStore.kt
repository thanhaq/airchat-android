package dev.offlinemesh.airchat.store

import android.content.Context
import dev.offlinemesh.airchat.model.ReceivedFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesReceivedFileStore(context: Context) : ReceivedFileStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("airchat_received_files", Context.MODE_PRIVATE)
    private val filesDir = File(appContext.filesDir, FILE_DIR)
    private val cipher = AndroidKeyStoreCipher("airchat_received_files_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadReceivedFiles(): List<ReceivedFile> {
        val metadata = loadMetadata()
        return metadata.mapNotNull { stored ->
            val encryptedFile = File(filesDir, stored.storageName)
            if (!encryptedFile.exists()) return@mapNotNull null
            val bytes = runCatching { cipher.decryptBytes(encryptedFile.readBytes()) }.getOrNull()
                ?: return@mapNotNull null
            if (bytes.size != stored.totalBytes || sha256(bytes) != stored.sha256) {
                return@mapNotNull null
            }
            ReceivedFile(
                id = stored.id,
                fileName = stored.fileName,
                mimeType = stored.mimeType,
                totalBytes = stored.totalBytes,
                sha256 = stored.sha256,
                senderId = stored.senderId,
                senderName = stored.senderName,
                channel = stored.channel,
                receivedAt = stored.receivedAt,
                bytes = bytes
            )
        }.sortedBy { it.receivedAt }
    }

    override fun saveReceivedFiles(files: List<ReceivedFile>) {
        filesDir.mkdirs()
        val metadata = files.map { file ->
            val storageName = storageName(file.id)
            File(filesDir, storageName).writeBytes(cipher.encryptBytes(file.bytes))
            StoredReceivedFile(
                id = file.id,
                fileName = file.fileName,
                mimeType = file.mimeType,
                totalBytes = file.totalBytes,
                sha256 = file.sha256,
                senderId = file.senderId,
                senderName = file.senderName,
                channel = file.channel,
                receivedAt = file.receivedAt,
                storageName = storageName
            )
        }
        val retained = metadata.map { it.storageName }.toSet()
        filesDir.listFiles()?.forEach { file ->
            if (file.name !in retained) runCatching { file.delete() }
        }
        saveMetadata(metadata)
    }

    override fun clear() {
        prefs.edit().remove(KEY_METADATA).apply()
        filesDir.listFiles()?.forEach { file -> runCatching { file.delete() } }
        runCatching { filesDir.delete() }
        runCatching { cipher.deleteKey() }
    }

    private fun loadMetadata(): List<StoredReceivedFile> {
        val raw = prefs.getString(KEY_METADATA, null) ?: return emptyList()
        val decoded = runCatching { cipher.decrypt(raw) }.getOrElse { raw }
        return runCatching { json.decodeFromString<List<StoredReceivedFile>>(decoded) }.getOrDefault(emptyList())
    }

    private fun saveMetadata(metadata: List<StoredReceivedFile>) {
        val raw = json.encodeToString(metadata)
        val encrypted = runCatching { cipher.encrypt(raw) }.getOrNull() ?: return
        prefs.edit().putString(KEY_METADATA, encrypted).apply()
    }

    private fun storageName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" } + ".bin"

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class StoredReceivedFile(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val totalBytes: Int,
        val sha256: String,
        val senderId: String,
        val senderName: String,
        val channel: String,
        val receivedAt: Long,
        val storageName: String
    )

    private companion object {
        const val FILE_DIR = "received-files"
        const val KEY_METADATA = "metadata"
    }
}
