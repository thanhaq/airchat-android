package dev.offlinemesh.airchat.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferCodecTest {
    @Test
    fun chunksAndReassemblesBytesWithHashVerification() {
        val bytes = "hello from an offline file transfer".toByteArray()

        val plan = FileTransferCodec.createPlan(
            fileName = "field-notes.txt",
            mimeType = "text/plain",
            bytes = bytes,
            chunkSize = 8,
            transferId = "transfer-1"
        )
        val reassembled = FileTransferCodec.reassemble(plan.manifest, plan.chunks)

        assertEquals(5, plan.manifest.totalChunks)
        assertArrayEquals(bytes, reassembled)
    }

    @Test
    fun rejectsMissingChunks() {
        val plan = FileTransferCodec.createPlan(
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            bytes = ByteArray(20) { it.toByte() },
            chunkSize = 5
        )

        assertNull(FileTransferCodec.reassemble(plan.manifest, plan.chunks.dropLast(1)))
    }

    @Test
    fun rejectsTamperedManifestHash() {
        val plan = FileTransferCodec.createPlan(
            fileName = "notes.txt",
            mimeType = "text/plain",
            bytes = "trusted bytes".toByteArray(),
            chunkSize = 4
        )
        val tampered = plan.manifest.copy(sha256 = "00")

        assertNull(FileTransferCodec.reassemble(tampered, plan.chunks))
    }

    @Test
    fun sanitizesUnsafeFileNames() {
        val plan = FileTransferCodec.createPlan(
            fileName = "../bad:name.txt",
            mimeType = "",
            bytes = byteArrayOf(1, 2, 3)
        )

        assertEquals(".._bad_name.txt", plan.manifest.fileName)
        assertEquals("application/octet-stream", plan.manifest.mimeType)
    }
}
