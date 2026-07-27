package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.CourierPacket
import dev.offlinemesh.airchat.model.CourierPolicy

class InMemoryCourierStore : CourierStore {
    private var packets = emptyList<CourierPacket>()
    private var policy = CourierPolicy.Default

    override fun loadCourierPackets(): List<CourierPacket> = packets

    override fun saveCourierPackets(packets: List<CourierPacket>) {
        this.packets = packets
    }

    override fun loadCourierPolicy(): CourierPolicy = policy

    override fun saveCourierPolicy(policy: CourierPolicy) {
        this.policy = policy.sanitized()
    }

    override fun clear() {
        packets = emptyList()
        policy = CourierPolicy.Default
    }
}
