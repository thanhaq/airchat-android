package dev.offlinemesh.airchat.store

class InMemoryRoomPreferencesStore : RoomPreferencesStore {
    private var knownRooms = emptySet<String>()
    private var pinnedRooms = emptySet<String>()
    private var roomOrder = emptyList<String>()

    override fun loadKnownRooms(): Set<String> = knownRooms

    override fun saveKnownRooms(rooms: Set<String>) {
        knownRooms = rooms.toSet()
    }

    override fun loadPinnedRooms(): Set<String> = pinnedRooms

    override fun savePinnedRooms(rooms: Set<String>) {
        pinnedRooms = rooms.toSet()
    }

    override fun loadRoomOrder(): List<String> = roomOrder

    override fun saveRoomOrder(rooms: List<String>) {
        roomOrder = rooms.toList()
    }

    override fun clear() {
        knownRooms = emptySet()
        pinnedRooms = emptySet()
        roomOrder = emptyList()
    }
}
