package dev.offlinemesh.airchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {
    @Test
    fun encodesFixedVersionFiveMatrix() {
        val matrix = QrCodeEncoder.encodeText("AIRCHAT-SAFETY:ABCDEF")

        assertEquals(37, matrix.size)
        assertTrue(matrix.isDark(3, 3))
        assertFalse(matrix.isDark(3, 1))
        assertTrue(matrix.isDark(33, 3))
        assertTrue(matrix.isDark(3, 33))
        assertTrue(matrix.isDark(30, 30))
    }

    @Test
    fun matrixChangesWithPayload() {
        val first = QrCodeEncoder.encodeText("AIRCHAT-SAFETY:FIRST")
        val second = QrCodeEncoder.encodeText("AIRCHAT-SAFETY:SECOND")

        var differences = 0
        for (y in 0 until first.size) {
            for (x in 0 until first.size) {
                if (first.isDark(x, y) != second.isDark(x, y)) differences++
            }
        }

        assertNotEquals(0, differences)
    }

    @Test
    fun rejectsPayloadTooLargeForFixedVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeEncoder.encodeText("x".repeat(107))
        }
    }
}
