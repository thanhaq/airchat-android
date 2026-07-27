package dev.offlinemesh.airchat.store

class InMemoryRoomPreferencesStore : RoomPreferencesStore {
    private var knownRooms = emptySet<String>()
    private var pinnedRooms = emptySet<String>()

    override fun loadKnownRooms(): Set<String> = knownRooms

    override fun saveKnownRooms(rooms: Set<String>) {
        knownRooms = rooms.toSet()
    }

    override fun loadPinnedRooms(): Set<String> = pinnedRooms

    override fun savePinnedRooms(rooms: Set<String>) {
        pinnedRooms = rooms.toSet()
    }

    override fun clear() {
        knownRooms = emptySet()
        pinnedRooms = emptySet()
    }
}
