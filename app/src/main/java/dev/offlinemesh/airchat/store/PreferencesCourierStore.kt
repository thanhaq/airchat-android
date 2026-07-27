package dev.offlinemesh.airchat.store

import android.content.Context
import dev.offlinemesh.airchat.model.CourierPacket
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesCourierStore(context: Context) : CourierStore {
    private val prefs = context.getSharedPreferences("airchat_courier", Context.MODE_PRIVATE)
    private val cipher = AndroidKeyStoreCipher("airchat_courier_store_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadCourierPackets(): List<CourierPacket> {
        val raw = prefs.getString(KEY_PACKETS, null) ?: return emptyList()
        val decoded = runCatching { cipher.decrypt(raw) }.getOrElse { raw }
        return runCatching { json.decodeFromString<List<CourierPacket>>(decoded) }.getOrDefault(emptyList())
    }

    override fun saveCourierPackets(packets: List<CourierPacket>) {
        val raw = json.encodeToString(packets)
        val encrypted = runCatching { cipher.encrypt(raw) }.getOrNull() ?: return
        prefs.edit().putString(KEY_PACKETS, encrypted).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_PACKETS).apply()
        runCatching { cipher.deleteKey() }
    }

    private companion object {
        const val KEY_PACKETS = "packets"
    }
}
