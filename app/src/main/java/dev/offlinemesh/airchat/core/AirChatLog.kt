package dev.offlinemesh.airchat.core

import android.util.Log
import java.util.Locale

enum class AirChatLogLevel {
    Info,
    Warning,
    Error
}

object AirChatLog {
    const val TAG = "AirChat"

    fun diagnostic(category: String, detail: String) {
        val message = format(category, detail)
        if (!isAndroidRuntime()) return
        runCatching {
            when (levelFor(category, detail)) {
                AirChatLogLevel.Info -> Log.i(TAG, message)
                AirChatLogLevel.Warning -> Log.w(TAG, message)
                AirChatLogLevel.Error -> Log.e(TAG, message)
            }
        }
    }

    fun throwableLabel(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        val message = error.message
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_THROWABLE_MESSAGE_LENGTH)
            ?.takeIf { it.isNotBlank() }
        return message?.let { "$type: $it" } ?: type
    }

    internal fun format(category: String, detail: String): String =
        "${sanitizeCategory(category)}: ${sanitizeDetail(detail)}"

    internal fun levelFor(category: String, detail: String): AirChatLogLevel {
        val combined = "$category $detail".lowercase(Locale.US)
        return when {
            combined.contains("failed") ||
                combined.contains("disabled") ||
                combined.contains("signature mismatch") ||
                combined.contains("invalid") -> AirChatLogLevel.Error

            combined.contains("degraded") ||
                combined.contains("unavailable") ||
                combined.contains("dropped") ||
                combined.contains("blocked") ||
                combined.contains("conflict") ||
                combined.contains("rate-limited") -> AirChatLogLevel.Warning

            else -> AirChatLogLevel.Info
        }
    }

    internal fun sanitizeDetail(detail: String): String =
        detail.replace(Regex("\\s+"), " ").trim().take(MAX_LOG_DETAIL_LENGTH)

    private fun sanitizeCategory(category: String): String =
        category
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "-")
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "event" }
            .take(MAX_CATEGORY_LENGTH)

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

    private const val MAX_CATEGORY_LENGTH = 32
    private const val MAX_LOG_DETAIL_LENGTH = 160
    private const val MAX_THROWABLE_MESSAGE_LENGTH = 80
}
