package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.CourierPacket
import dev.offlinemesh.airchat.model.CourierPolicy

interface CourierStore {
    fun loadCourierPackets(): List<CourierPacket>
    fun saveCourierPackets(packets: List<CourierPacket>)
    fun loadCourierPolicy(): CourierPolicy
    fun saveCourierPolicy(policy: CourierPolicy)
    fun clear()
}
