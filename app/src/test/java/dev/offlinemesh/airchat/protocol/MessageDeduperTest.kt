package dev.offlinemesh.airchat.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeduperTest {
    @Test
    fun rememberReturnsTrueOnlyForNewIds() {
        val deduper = MessageDeduper(maxEntries = 4)

        assertTrue(deduper.remember("one"))
        assertFalse(deduper.remember("one"))
    }

    @Test
    fun rememberEvictsOldEntries() {
        val deduper = MessageDeduper(maxEntries = 2)

        assertTrue(deduper.remember("one"))
        assertTrue(deduper.remember("two"))
        assertTrue(deduper.remember("three"))
        assertTrue(deduper.remember("one"))
    }
}
