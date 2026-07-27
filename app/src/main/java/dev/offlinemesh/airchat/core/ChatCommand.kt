package dev.offlinemesh.airchat.core

sealed interface ChatCommand {
    data class SendText(val body: String) : ChatCommand
    data class JoinRoom(val channel: String) : ChatCommand
    data object LeaveDirect : ChatCommand
    data class DirectMessage(val target: String, val body: String) : ChatCommand
    data class BlockPeer(val target: String) : ChatCommand
    data class UnblockPeer(val target: String) : ChatCommand
    data class Action(val body: String) : ChatCommand
    data class LockRoom(val passphrase: String) : ChatCommand
    data class RotateRoom(val passphrase: String) : ChatCommand
    data object UnlockRoom : ChatCommand
    data object ShowRoomCode : ChatCommand
    data object ShowPeers : ChatCommand
    data object ShowBlockedPeers : ChatCommand
    data object ShowHelp : ChatCommand
    data class Unknown(val name: String) : ChatCommand
}

object ChatCommandParser {
    fun parse(input: String): ChatCommand {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return ChatCommand.SendText(trimmed)

        val commandText = trimmed.drop(1)
        val name = commandText.substringBefore(' ').lowercase()
        val rest = commandText.substringAfter(' ', "").trim()
        return when (name) {
            "join", "j" -> {
                val room = sanitizeChannel(rest.substringBefore(' '))
                if (room.isBlank()) ChatCommand.Unknown(name) else ChatCommand.JoinRoom(room)
            }

            "lock", "key" -> if (rest.isBlank()) {
                ChatCommand.Unknown(name)
            } else {
                ChatCommand.LockRoom(rest.take(MAX_PASSPHRASE_LENGTH))
            }

            "rotate", "rekey" -> if (rest.isBlank()) {
                ChatCommand.Unknown(name)
            } else {
                ChatCommand.RotateRoom(rest.take(MAX_PASSPHRASE_LENGTH))
            }

            "code", "room-code" -> ChatCommand.ShowRoomCode
            "unlock", "clear-key" -> ChatCommand.UnlockRoom
            "room", "lobby" -> ChatCommand.LeaveDirect
            "msg", "dm", "w", "tell" -> directMessage(name, rest)
            "block" -> if (rest.isBlank()) {
                ChatCommand.ShowBlockedPeers
            } else {
                ChatCommand.BlockPeer(rest.substringBefore(' ').removePrefix("@").take(MAX_PEER_QUERY_LENGTH))
            }

            "unblock" -> if (rest.isBlank()) {
                ChatCommand.Unknown(name)
            } else {
                ChatCommand.UnblockPeer(rest.substringBefore(' ').removePrefix("@").take(MAX_PEER_QUERY_LENGTH))
            }

            "blocks", "blocked" -> ChatCommand.ShowBlockedPeers
            "me" -> if (rest.isBlank()) ChatCommand.Unknown(name) else ChatCommand.Action(rest.take(MAX_MESSAGE_LENGTH))
            "who" -> ChatCommand.ShowPeers
            "help", "?" -> ChatCommand.ShowHelp
            else -> ChatCommand.Unknown(name.ifBlank { "/" })
        }
    }

    fun sanitizeChannel(value: String): String {
        return value
            .removePrefix("#")
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { DEFAULT_CHANNEL }
            .take(MAX_CHANNEL_LENGTH)
    }

    private fun directMessage(commandName: String, rest: String): ChatCommand {
        val target = rest.substringBefore(' ').removePrefix("@").trim()
        val body = rest.substringAfter(' ', "").trim().take(MAX_MESSAGE_LENGTH)
        return if (target.isBlank() || body.isBlank()) {
            ChatCommand.Unknown(commandName)
        } else {
            ChatCommand.DirectMessage(target = target, body = body)
        }
    }

    private const val DEFAULT_CHANNEL = "lobby"
    private const val MAX_CHANNEL_LENGTH = 32
    private const val MAX_MESSAGE_LENGTH = 2_000
    private const val MAX_PASSPHRASE_LENGTH = 256
    private const val MAX_PEER_QUERY_LENGTH = 128
}
