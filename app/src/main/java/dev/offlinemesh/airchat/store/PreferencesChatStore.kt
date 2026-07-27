package dev.offlinemesh.airchat.store

import android.content.Context
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.OutboxItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesChatStore(context: Context) : ChatStore {
    private val prefs = context.getSharedPreferences("airchat_messages", Context.MODE_PRIVATE)
    private val cipher = AndroidKeyStoreCipher("airchat_message_store_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadMessages(): List<ChatMessage> =
        loadString(KEY_MESSAGES)?.let {
            runCatching { json.decodeFromString<List<ChatMessage>>(it) }.getOrNull()
        } ?: emptyList()

    override fun saveMessages(messages: List<ChatMessage>) {
        saveString(KEY_MESSAGES, json.encodeToString(messages))
    }

    override fun loadOutbox(): List<OutboxItem> =
        loadString(KEY_OUTBOX)?.let {
            runCatching { json.decodeFromString<List<OutboxItem>>(it) }.getOrNull()
        } ?: emptyList()

    override fun saveOutbox(items: List<OutboxItem>) {
        saveString(KEY_OUTBOX, json.encodeToString(items))
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_MESSAGES)
            .remove(KEY_OUTBOX)
            .apply()
        runCatching { cipher.deleteKey() }
    }

    private fun loadString(key: String): String? {
        return prefs.getString(key, null)?.let { stored ->
            runCatching { cipher.decrypt(stored) }.getOrElse { stored }
        }
    }

    private fun saveString(key: String, value: String) {
        val encrypted = runCatching { cipher.encrypt(value) }.getOrNull() ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    private companion object {
        const val KEY_MESSAGES = "messages"
        const val KEY_OUTBOX = "outbox"
    }
}
