package dev.offlinemesh.airchat.core

import dev.offlinemesh.airchat.model.ReceivedFile

enum class ReceivedFilePreviewKind {
    Text,
    Image,
    Document,
    Binary
}

data class ReceivedFilePreview(
    val kind: ReceivedFilePreviewKind,
    val label: String,
    val body: String? = null
)

object ReceivedFilePreviewer {
    fun preview(file: ReceivedFile, maxTextChars: Int = DEFAULT_TEXT_PREVIEW_CHARS): ReceivedFilePreview {
        val mimeType = file.mimeType.lowercase()
        val extension = file.fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

        if (isTextLike(mimeType, extension)) {
            return ReceivedFilePreview(
                kind = ReceivedFilePreviewKind.Text,
                label = "Text",
                body = textPreview(file.bytes, maxTextChars)
            )
        }

        if (isImageLike(mimeType, extension)) {
            val sizeLabel = imageSize(file.bytes)?.let { (width, height) -> " / ${width}x$height" } ?: ""
            return ReceivedFilePreview(
                kind = ReceivedFilePreviewKind.Image,
                label = "Image$sizeLabel"
            )
        }

        if (isDocumentLike(mimeType, extension)) {
            return ReceivedFilePreview(
                kind = ReceivedFilePreviewKind.Document,
                label = documentLabel(mimeType, extension)
            )
        }

        return ReceivedFilePreview(
            kind = ReceivedFilePreviewKind.Binary,
            label = "Binary file"
        )
    }

    private fun isTextLike(mimeType: String, extension: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType in STRUCTURED_TEXT_MIME_TYPES ||
            extension in TEXT_EXTENSIONS
    }

    private fun isImageLike(mimeType: String, extension: String): Boolean {
        return mimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS
    }

    private fun isDocumentLike(mimeType: String, extension: String): Boolean {
        return mimeType in DOCUMENT_MIME_TYPES || extension in DOCUMENT_EXTENSIONS
    }

    private fun documentLabel(mimeType: String, extension: String): String {
        return when {
            mimeType == "application/pdf" || extension == "pdf" -> "PDF document"
            extension == "doc" || extension == "docx" -> "Word document"
            extension == "xls" || extension == "xlsx" -> "Spreadsheet"
            extension == "ppt" || extension == "pptx" -> "Presentation"
            extension == "zip" -> "ZIP archive"
            else -> "Document"
        }
    }

    private fun textPreview(bytes: ByteArray, maxTextChars: Int): String {
        val normalizedLimit = maxTextChars.coerceIn(1, 1_000)
        val decoded = bytes.decodeToString()
            .replace('\u0000', ' ')
            .lineSequence()
            .map { line -> line.trimEnd() }
            .joinToString("\n")
            .trim()
        if (decoded.isEmpty()) return "(empty text file)"
        val truncated = decoded.length > normalizedLimit
        val body = decoded.take(normalizedLimit).trimEnd()
        return if (truncated) "$body..." else body
    }

    private fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
        return pngSize(bytes) ?: jpegSize(bytes) ?: gifSize(bytes)
    }

    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (!bytes.take(signature.size).toByteArray().contentEquals(signature)) return null
        return readUInt32Be(bytes, 16) to readUInt32Be(bytes, 20)
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0].toInt() and 0xff != 0xff || bytes[1].toInt() and 0xff != 0xd8) {
            return null
        }
        var offset = 2
        while (offset + 9 < bytes.size) {
            if (bytes[offset].toInt() and 0xff != 0xff) return null
            val marker = bytes[offset + 1].toInt() and 0xff
            offset += 2
            if (marker == 0xd9 || marker == 0xda) return null
            if (offset + 2 > bytes.size) return null
            val segmentLength = readUInt16Be(bytes, offset)
            if (segmentLength < 2 || offset + segmentLength > bytes.size) return null
            if (marker in JPEG_SIZE_MARKERS) {
                val height = readUInt16Be(bytes, offset + 3)
                val width = readUInt16Be(bytes, offset + 5)
                return width to height
            }
            offset += segmentLength
        }
        return null
    }

    private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        val header = bytes.decodeToString(0, 6)
        if (header != "GIF87a" && header != "GIF89a") return null
        val width = readUInt16Le(bytes, 6)
        val height = readUInt16Le(bytes, 8)
        return width to height
    }

    private fun readUInt16Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readUInt32Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private const val DEFAULT_TEXT_PREVIEW_CHARS = 180

    private val STRUCTURED_TEXT_MIME_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
        "application/javascript"
    )

    private val TEXT_EXTENSIONS = setOf(
        "csv",
        "json",
        "log",
        "md",
        "txt",
        "xml",
        "yaml",
        "yml"
    )

    private val IMAGE_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "webp")

    private val DOCUMENT_MIME_TYPES = setOf(
        "application/pdf",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/zip"
    )

    private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "pdf", "ppt", "pptx", "xls", "xlsx", "zip")

    private val JPEG_SIZE_MARKERS = setOf(
        0xc0,
        0xc1,
        0xc2,
        0xc3,
        0xc5,
        0xc6,
        0xc7,
        0xc9,
        0xca,
        0xcb,
        0xcd,
        0xce,
        0xcf
    )
}
