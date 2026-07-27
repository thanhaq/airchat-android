package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {
    @Test
    fun fingerprintIsStableRegardlessOfKeyOrder() {
        val first = SafetyNumber.fingerprint("key-a", "key-b")
        val second = SafetyNumber.fingerprint("key-b", "key-a")

        assertEquals(first, second)
    }

    @Test
    fun shortCodeUsesThreeGroups() {
        val code = SafetyNumber.shortCode("key-a", "key-b")

        assertTrue(Regex("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}").matches(code))
    }
}
