package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ReceivedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedFilePreviewerTest {
    @Test
    fun previewsTextFilesWithBoundedSnippet() {
        val file = receivedFile(
            fileName = "notes.txt",
            mimeType = "text/plain",
            bytes = "line one\nline two\nline three".toByteArray()
        )

        val preview = ReceivedFilePreviewer.preview(file, maxTextChars = 12)

        assertEquals(ReceivedFilePreviewKind.Text, preview.kind)
        assertEquals("Text", preview.label)
        assertEquals("line one\nlin...", preview.body)
    }

    @Test
    fun treatsStructuredTextExtensionsAsText() {
        val file = receivedFile(
            fileName = "diagnostics.json",
            mimeType = "application/octet-stream",
            bytes = """{"schema":"airchat"}""".toByteArray()
        )

        val preview = ReceivedFilePreviewer.preview(file)

        assertEquals(ReceivedFilePreviewKind.Text, preview.kind)
        assertTrue(preview.body!!.contains("schema"))
    }

    @Test
    fun previewsPngImagesWithDimensions() {
        val file = receivedFile(
            fileName = "map.png",
            mimeType = "image/png",
            bytes = pngHeader(width = 320, height = 180)
        )

        val preview = ReceivedFilePreviewer.preview(file)

        assertEquals(ReceivedFilePreviewKind.Image, preview.kind)
        assertEquals("Image / 320x180", preview.label)
    }

    @Test
    fun labelsKnownDocuments() {
        val file = receivedFile(
            fileName = "field-report.pdf",
            mimeType = "application/pdf",
            bytes = "%PDF".toByteArray()
        )

        val preview = ReceivedFilePreviewer.preview(file)

        assertEquals(ReceivedFilePreviewKind.Document, preview.kind)
        assertEquals("PDF document", preview.label)
    }

    @Test
    fun fallsBackToBinaryForUnknownPayloads() {
        val file = receivedFile(
            fileName = "payload.bin",
            mimeType = "application/octet-stream",
            bytes = byteArrayOf(0, 1, 2, 3)
        )

        val preview = ReceivedFilePreviewer.preview(file)

        assertEquals(ReceivedFilePreviewKind.Binary, preview.kind)
        assertEquals("Binary file", preview.label)
    }

    private fun receivedFile(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): ReceivedFile {
        return ReceivedFile(
            id = fileName,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = bytes.size,
            sha256 = "hash-$fileName",
            bytes = bytes,
            channel = "lobby",
            senderId = "sender",
            senderName = "Alice",
            receivedAt = 1_000L
        )
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(bytes)
        writeUInt32Be(bytes, offset = 16, value = width)
        writeUInt32Be(bytes, offset = 20, value = height)
        return bytes
    }

    private fun writeUInt32Be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
