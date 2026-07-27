package dev.offlinemesh.airchat.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCommandParserTest {
    @Test
    fun parsesPlainTextAsSendText() {
        assertEquals(ChatCommand.SendText("hello mesh"), ChatCommandParser.parse(" hello mesh "))
    }

    @Test
    fun parsesJoinAndSanitizesChannelNames() {
        assertEquals(ChatCommand.JoinRoom("storm"), ChatCommandParser.parse("/join #storm ops"))
        assertEquals(ChatCommand.JoinRoom("base-camp"), ChatCommandParser.parse("/j #base-camp"))
    }

    @Test
    fun parsesRoomAsLeaveDirect() {
        assertEquals(ChatCommand.LeaveDirect, ChatCommandParser.parse("/room"))
        assertEquals(ChatCommand.LeaveDirect, ChatCommandParser.parse("/lobby"))
    }

    @Test
    fun parsesDirectMessageAliases() {
        assertEquals(
            ChatCommand.DirectMessage(target = "abc123", body = "bring radios"),
            ChatCommandParser.parse("/msg @abc123 bring radios")
        )
        assertEquals(
            ChatCommand.DirectMessage(target = "alice", body = "copy"),
            ChatCommandParser.parse("/dm alice copy")
        )
    }

    @Test
    fun parsesActionWhoAndHelp() {
        assertEquals(ChatCommand.Action("checks the relay"), ChatCommandParser.parse("/me checks the relay"))
        assertEquals(ChatCommand.ShowPeers, ChatCommandParser.parse("/who"))
        assertEquals(ChatCommand.ShowHelp, ChatCommandParser.parse("/help"))
    }

    @Test
    fun parsesPrivateRoomCommands() {
        assertEquals(ChatCommand.LockRoom("shared field key"), ChatCommandParser.parse("/lock shared field key"))
        assertEquals(ChatCommand.LockRoom("shared field key"), ChatCommandParser.parse("/key shared field key"))
        assertEquals(ChatCommand.RotateRoom("new shared field key"), ChatCommandParser.parse("/rotate new shared field key"))
        assertEquals(ChatCommand.RotateRoom("new shared field key"), ChatCommandParser.parse("/rekey new shared field key"))
        assertEquals(ChatCommand.ShowRoomCode, ChatCommandParser.parse("/code"))
        assertEquals(ChatCommand.ShowRoomCode, ChatCommandParser.parse("/room-code"))
        assertEquals(ChatCommand.UnlockRoom, ChatCommandParser.parse("/unlock"))
        assertEquals(ChatCommand.UnlockRoom, ChatCommandParser.parse("/clear-key"))
    }

    @Test
    fun rejectsIncompleteCommands() {
        assertEquals(ChatCommand.Unknown("msg"), ChatCommandParser.parse("/msg alice"))
        assertEquals(ChatCommand.Unknown("me"), ChatCommandParser.parse("/me"))
        assertEquals(ChatCommand.Unknown("lock"), ChatCommandParser.parse("/lock"))
        assertEquals(ChatCommand.Unknown("rotate"), ChatCommandParser.parse("/rotate"))
        assertEquals(ChatCommand.Unknown("nope"), ChatCommandParser.parse("/nope"))
    }

    @Test
    fun channelSanitizerFallsBackAndClamps() {
        assertEquals("lobby", ChatCommandParser.sanitizeChannel("###"))
        assertEquals(
            "abcdefghijklmnopqrstuvwxzy012345".take(32),
            ChatCommandParser.sanitizeChannel("#abcdefghijklmnopqrstuvwxzy0123456789")
        )
    }
}
