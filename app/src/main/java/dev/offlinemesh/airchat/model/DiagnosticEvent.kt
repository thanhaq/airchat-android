package dev.offlinemesh.airchat.model

data class DiagnosticEvent(
    val createdAt: Long,
    val category: String,
    val detail: String
)
