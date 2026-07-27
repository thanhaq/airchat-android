package dev.offlinemesh.airchat.core

import androidx.lifecycle.ViewModel
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.transport.MeshRouter
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val localPeerId: String,
    val nickname: String,
    val localPublicKey: String,
    val channel: String,
    val directPeer: Peer?,
    val composer: String,
    val peers: List<Peer>,
    val messages: List<ChatMessage>,
    val receivedFiles: List<ReceivedFile>,
    val courierQueueSize: Int,
    val transportStatuses: List<TransportStatus>
)

private data class ComposerState(
    val text: String,
    val channel: String,
    val directPeerId: String?
)

private data class RouterState(
    val peers: List<Peer>,
    val messages: List<ChatMessage>,
    val files: List<ReceivedFile>,
    val courierQueueSize: Int,
    val statuses: List<TransportStatus>
)

class ChatViewModel(
    private val router: MeshRouter
) : ViewModel() {
    private val composer = MutableStateFlow("")
    private val channel = MutableStateFlow("lobby")
    private val directPeerId = MutableStateFlow<String?>(null)

    private val composerState = combine(composer, channel, directPeerId) { text, channelName, directId ->
        ComposerState(text = text, channel = channelName, directPeerId = directId)
    }

    private val routerState = combine(
        router.peers,
        router.messages,
        router.receivedFiles,
        router.courierQueueSize,
        router.transportStatuses
    ) { peers, messages, files, courierQueueSize, statuses ->
        RouterState(
            peers = peers,
            messages = messages,
            files = files,
            courierQueueSize = courierQueueSize,
            statuses = statuses
        )
    }

    val uiState: StateFlow<ChatUiState> = combine(composerState, routerState) { composerState, routerState ->
        val sortedPeers = routerState.peers.sortedByDescending { it.lastSeenAt }
        val directPeer = sortedPeers.firstOrNull { it.id == composerState.directPeerId }
        val conversation = directPeer?.let { Conversation.Direct(it.id) }
            ?: Conversation.Room(composerState.channel)
        ChatUiState(
            localPeerId = router.localPeerId,
            nickname = router.localName,
            localPublicKey = router.localPublicKey,
            channel = composerState.channel,
            directPeer = directPeer,
            composer = composerState.text,
            peers = sortedPeers,
            messages = ConversationFilter.apply(routerState.messages, conversation),
            receivedFiles = ConversationFilter.applyFiles(routerState.files, conversation),
            courierQueueSize = routerState.courierQueueSize,
            transportStatuses = routerState.statuses
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            localPeerId = router.localPeerId,
            nickname = router.localName,
            localPublicKey = router.localPublicKey,
            channel = "lobby",
            directPeer = null,
            composer = "",
            peers = emptyList(),
            messages = emptyList(),
            receivedFiles = emptyList(),
            courierQueueSize = 0,
            transportStatuses = emptyList()
        )
    )

    fun updateComposer(value: String) {
        composer.value = value.take(2_000)
    }

    fun updateChannel(value: String) {
        channel.value = ChatCommandParser.sanitizeChannel(value)
    }

    fun sendCurrentMessage() {
        val input = composer.value.trim()
        if (input.isEmpty()) return
        composer.value = ""
        when (val command = ChatCommandParser.parse(input)) {
            is ChatCommand.SendText -> sendText(command.body, input)
            is ChatCommand.JoinRoom -> joinRoom(command.channel)
            ChatCommand.LeaveDirect -> leaveDirect()
            is ChatCommand.DirectMessage -> sendCommandDirectMessage(command, input)
            is ChatCommand.Action -> sendText("* ${router.localName} ${command.body}", input)
            ChatCommand.ShowPeers -> showPeerNotice()
            ChatCommand.ShowHelp -> router.appendLocalNotice(activeConversationChannel(), COMMAND_HELP)
            is ChatCommand.Unknown -> {
                router.appendLocalNotice(activeConversationChannel(), "Unknown command: /${command.name}. Try /help.")
            }
        }
    }

    private fun sendText(body: String, originalInput: String) {
        viewModelScope.launch {
            val directId = uiState.value.directPeer?.id
            if (directId != null) {
                val sent = router.sendDirectMessage(peerId = directId, body = body)
                if (!sent) composer.value = originalInput
            } else {
                router.sendChannelMessage(channel = channel.value, body = body)
            }
        }
    }

    private fun joinRoom(room: String) {
        channel.value = room
        directPeerId.value = null
        router.appendLocalNotice(channel.value, "Joined #$room")
    }

    private fun leaveDirect() {
        directPeerId.value = null
        router.appendLocalNotice(channel.value, "Returned to #${channel.value}")
    }

    private fun sendCommandDirectMessage(command: ChatCommand.DirectMessage, originalInput: String) {
        val peer = resolvePeer(command.target)
        if (peer == null) {
            router.appendLocalNotice(activeConversationChannel(), "No peer matches ${command.target}")
            return
        }
        directPeerId.value = peer.id
        viewModelScope.launch {
            val sent = router.sendDirectMessage(peerId = peer.id, body = command.body)
            if (!sent) composer.value = originalInput
        }
    }

    private fun showPeerNotice() {
        val peers = uiState.value.peers
        val body = if (peers.isEmpty()) {
            "No peers nearby"
        } else {
            peers.joinToString { peer -> "${peer.name} (${peer.id.take(6)})" }
        }
        router.appendLocalNotice(activeConversationChannel(), body)
    }

    private fun resolvePeer(query: String): Peer? {
        val normalized = query.removePrefix("@").lowercase()
        return uiState.value.peers.firstOrNull { peer ->
            peer.id.equals(normalized, ignoreCase = true) ||
                peer.id.lowercase().startsWith(normalized) ||
                peer.name.equals(query, ignoreCase = true) ||
                peer.name.lowercase().startsWith(normalized)
        }
    }

    private fun activeConversationChannel(): String {
        return uiState.value.directPeer?.let { "dm:${it.id}" } ?: channel.value
    }

    fun retryDiscovery() {
        router.refreshTransports()
    }

    fun connectWifiPeer(peer: Peer) {
        router.connectWifiDirectPeer(peer.id)
    }

    fun selectDirectPeer(peer: Peer) {
        directPeerId.value = peer.id
    }

    fun clearDirectPeer() {
        directPeerId.value = null
    }

    fun trustPeer(peer: Peer) {
        router.trustPeer(peer.id)
    }

    fun forgetPeer(peer: Peer) {
        router.forgetTrustedPeer(peer.id)
    }

    fun sendFile(fileName: String, mimeType: String, bytes: ByteArray) {
        viewModelScope.launch {
            runCatching {
                val directPeer = uiState.value.directPeer
                if (directPeer != null) {
                    router.sendDirectFile(
                        peerId = directPeer.id,
                        fileName = fileName,
                        mimeType = mimeType,
                        bytes = bytes
                    )
                } else {
                    router.sendChannelFile(
                        channel = channel.value,
                        fileName = fileName,
                        mimeType = mimeType,
                        bytes = bytes
                    )
                }
            }
        }
    }
}

private const val COMMAND_HELP = "Commands: /join room, /room, /msg peer text, /me action, /who."
