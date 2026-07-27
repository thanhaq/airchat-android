package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.CourierPacket

class InMemoryCourierStore : CourierStore {
    private var packets = emptyList<CourierPacket>()

    override fun loadCourierPackets(): List<CourierPacket> = packets

    override fun saveCourierPackets(packets: List<CourierPacket>) {
        this.packets = packets
    }

    override fun clear() {
        packets = emptyList()
    }
}
