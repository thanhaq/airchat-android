package dev.offlinemesh.airchat.store

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesRoomPreferencesStore(context: Context) : RoomPreferencesStore {
    private val prefs = context.getSharedPreferences("airchat_room_preferences", Context.MODE_PRIVATE)
    private val cipher = AndroidKeyStoreCipher("airchat_room_preferences_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun loadKnownRooms(): Set<String> = loadRooms(KEY_KNOWN_ROOMS)

    override fun saveKnownRooms(rooms: Set<String>) {
        saveRooms(KEY_KNOWN_ROOMS, rooms)
    }

    override fun loadPinnedRooms(): Set<String> = loadRooms(KEY_PINNED_ROOMS)

    override fun savePinnedRooms(rooms: Set<String>) {
        saveRooms(KEY_PINNED_ROOMS, rooms)
    }

    override fun loadRoomOrder(): List<String> = loadRoomList(KEY_ROOM_ORDER)

    override fun saveRoomOrder(rooms: List<String>) {
        saveRoomList(KEY_ROOM_ORDER, rooms.distinct())
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_KNOWN_ROOMS)
            .remove(KEY_PINNED_ROOMS)
            .remove(KEY_ROOM_ORDER)
            .apply()
        runCatching { cipher.deleteKey() }
    }

    private fun loadRooms(key: String): Set<String> {
        return loadRoomList(key).toSet()
    }

    private fun loadRoomList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val decoded = runCatching { cipher.decrypt(raw) }.getOrElse { raw }
        return runCatching {
            json.decodeFromString<List<String>>(decoded).distinct()
        }.getOrDefault(emptyList())
    }

    private fun saveRooms(key: String, rooms: Set<String>) {
        saveRoomList(key, rooms.sorted())
    }

    private fun saveRoomList(key: String, rooms: List<String>) {
        val raw = json.encodeToString(rooms)
        val encrypted = runCatching { cipher.encrypt(raw) }.getOrNull() ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    private companion object {
        const val KEY_KNOWN_ROOMS = "known_rooms"
        const val KEY_PINNED_ROOMS = "pinned_rooms"
        const val KEY_ROOM_ORDER = "room_order"
    }
}
