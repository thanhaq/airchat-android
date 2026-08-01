package dev.offlinemesh.airchat.core

object SafetyShareFormatter {
    fun format(
        peerName: String,
        peerId: String,
        safetyNumber: String,
        safetyPayload: String
    ): String {
        val safeName = peerName.trim().ifBlank { "Unknown peer" }.take(MAX_PEER_NAME_LENGTH)
        val peerLabel = peerId.trim().take(MAX_PEER_ID_LENGTH).ifBlank { "unknown" }
        return buildString {
            appendLine("AirChat safety card")
            appendLine("Peer: $safeName")
            appendLine("Peer id: $peerLabel")
            appendLine("Safety number: $safetyNumber")
            appendLine("Payload: $safetyPayload")
            appendLine("Compare this out of band before trusting the peer key.")
        }.trimEnd()
    }

    private const val MAX_PEER_NAME_LENGTH = 48
    private const val MAX_PEER_ID_LENGTH = 24
}
