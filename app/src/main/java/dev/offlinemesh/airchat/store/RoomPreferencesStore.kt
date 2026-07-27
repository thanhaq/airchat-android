package dev.offlinemesh.airchat.store

interface RoomPreferencesStore {
    fun loadKnownRooms(): Set<String>
    fun saveKnownRooms(rooms: Set<String>)
    fun loadPinnedRooms(): Set<String>
    fun savePinnedRooms(rooms: Set<String>)
    fun loadRoomOrder(): List<String>
    fun saveRoomOrder(rooms: List<String>)
    fun clear()
}
