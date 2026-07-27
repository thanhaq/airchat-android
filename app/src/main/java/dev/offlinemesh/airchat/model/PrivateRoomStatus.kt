package dev.offlinemesh.airchat.model

data class PrivateRoomStatus(
    val channel: String,
    val verificationCode: String,
    val strengthLabel: String
)
