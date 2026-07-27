package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.CourierPacket

interface CourierStore {
    fun loadCourierPackets(): List<CourierPacket>
    fun saveCourierPackets(packets: List<CourierPacket>)
    fun clear()
}
