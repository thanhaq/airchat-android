package dev.offlinemesh.airchat.protocol

import java.util.LinkedHashMap

class MessageDeduper(private val maxEntries: Int = 2_048) {
    private val seen = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun remember(id: String): Boolean {
        val isNew = !seen.containsKey(id)
        seen[id] = System.currentTimeMillis()
        return isNew
    }

    @Synchronized
    fun clear() {
        seen.clear()
    }
}
