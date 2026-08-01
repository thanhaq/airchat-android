package dev.offlinemesh.airchat.core

import androidx.lifecycle.ViewModel
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.CourierPolicy
import dev.offlinemesh.airchat.model.DiagnosticEvent
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PrivateRoomStatus
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.RoomSummary
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.store.InMemoryRoomPreferencesStore
import dev.offlinemesh.airchat.store.RoomPreferencesStore
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
    val privateRoomEnabled: Boolean,
    val privateRoomCode: String?,
    val privateRoomStrength: String?,
    val rooms: List<RoomSummary>,
    val directPeer: Peer?,
    val composer: String,
    val peers: List<Peer>,
    val messages: List<ChatMessage>,
    val receivedFiles: List<ReceivedFile>,
    val courierQueueSize: Int,
    val courierEnabled: Boolean,
    val courierRetentionMinutes: Int,
    val blockedPeerIds: Set<String>,
    val blockedPeerCount: Int,
    val pinnedRoomCount: Int,
    val transportStatuses: List<TransportStatus>,
    val diagnosticEvents: List<DiagnosticEvent>
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
    val courierPolicy: CourierPolicy,
    val blockedPeerIds: Set<String>,
    val privateRooms: Map<String, PrivateRoomStatus>,
    val statuses: List<TransportStatus>,
    val diagnostics: List<DiagnosticEvent>
)

private data class RouterCoreState(
    val peers: List<Peer>,
    val messages: List<ChatMessage>,
    val files: List<ReceivedFile>,
    val courierQueueSize: Int,
    val courierPolicy: CourierPolicy,
    val blockedPeerIds: Set<String>,
    val privateRooms: Map<String, PrivateRoomStatus>
)

private data class RouterMetaState(
    val courierQueueSize: Int,
    val courierPolicy: CourierPolicy,
    val blockedPeerIds: Set<String>,
    val privateRooms: Map<String, PrivateRoomStatus>
)

private data class CourierState(
    val queueSize: Int,
    val policy: CourierPolicy
)

private data class RoomUiPreferences(
    val knownRooms: Set<String>,
    val readAtByRoom: Map<String, Long>,
    val pinnedRooms: Set<String>,
    val roomOrder: List<String>
)

class ChatViewModel(
    private val router: MeshRouter,
    private val roomPreferencesStore: RoomPreferencesStore = InMemoryRoomPreferencesStore()
) : ViewModel() {
    private val composer = MutableStateFlow("")
    private val channel = MutableStateFlow("lobby")
    private val directPeerId = MutableStateFlow<String?>(null)
    private val pinnedRooms = MutableStateFlow(loadRoomSet(roomPreferencesStore.loadPinnedRooms()))
    private val roomOrder = MutableStateFlow(loadRoomList(roomPreferencesStore.loadRoomOrder()))
    private val knownRooms = MutableStateFlow(loadRoomSet(roomPreferencesStore.loadKnownRooms(), includeLobby = true) + pinnedRooms.value)
    private val roomReadAt = MutableStateFlow(mapOf("lobby" to Long.MAX_VALUE))

    private val composerState = combine(composer, channel, directPeerId) { text, channelName, directId ->
        ComposerState(text = text, channel = channelName, directPeerId = directId)
    }

    private val courierState = combine(
        router.courierQueueSize,
        router.courierPolicy
    ) { queueSize, policy ->
        CourierState(queueSize = queueSize, policy = policy)
    }

    private val routerMetaState = combine(
        courierState,
        router.blockedPeerIds,
        router.privateRoomStatuses
    ) { courierState, blockedPeerIds, privateRooms ->
        RouterMetaState(
            courierQueueSize = courierState.queueSize,
            courierPolicy = courierState.policy,
            blockedPeerIds = blockedPeerIds,
            privateRooms = privateRooms
        )
    }

    private val routerCoreState = combine(
        router.peers,
        router.messages,
        router.receivedFiles,
        routerMetaState
    ) { peers, messages, files, metaState ->
        RouterCoreState(
            peers = peers,
            messages = messages,
            files = files,
            courierQueueSize = metaState.courierQueueSize,
            courierPolicy = metaState.courierPolicy,
            blockedPeerIds = metaState.blockedPeerIds,
            privateRooms = metaState.privateRooms
        )
    }

    private val routerState = combine(
        routerCoreState,
        router.transportStatuses,
        router.diagnostics
    ) { coreState, statuses, diagnostics ->
        RouterState(
            peers = coreState.peers,
            messages = coreState.messages,
            files = coreState.files,
            courierQueueSize = coreState.courierQueueSize,
            courierPolicy = coreState.courierPolicy,
            blockedPeerIds = coreState.blockedPeerIds,
            privateRooms = coreState.privateRooms,
            statuses = statuses,
            diagnostics = diagnostics
        )
    }

    private val roomUiPreferences = combine(
        knownRooms,
        roomReadAt,
        pinnedRooms,
        roomOrder
    ) { knownRooms, readAtByRoom, pinnedRooms, roomOrder ->
        RoomUiPreferences(
            knownRooms = knownRooms,
            readAtByRoom = readAtByRoom,
            pinnedRooms = pinnedRooms,
            roomOrder = roomOrder
        )
    }

    val uiState: StateFlow<ChatUiState> = combine(
        composerState,
        routerState,
        roomUiPreferences
    ) { composerState, routerState, roomPreferences ->
        val sortedPeers = routerState.peers.sortedByDescending { it.lastSeenAt }
        val directPeer = sortedPeers.firstOrNull { it.id == composerState.directPeerId }
        val privateRoom = routerState.privateRooms[composerState.channel]
        val conversation = directPeer?.let { Conversation.Direct(it.id) }
            ?: Conversation.Room(composerState.channel)
        val rooms = RoomDirectory.summarize(
            messages = routerState.messages,
            files = routerState.files,
            privateRooms = routerState.privateRooms,
            knownRooms = roomPreferences.knownRooms,
            pinnedRooms = roomPreferences.pinnedRooms,
            roomOrder = roomPreferences.roomOrder,
            selectedChannel = composerState.channel,
            readAtByRoom = roomPreferences.readAtByRoom
        )
        ChatUiState(
            localPeerId = router.localPeerId,
            nickname = router.localName,
            localPublicKey = router.localPublicKey,
            channel = composerState.channel,
            privateRoomEnabled = privateRoom != null,
            privateRoomCode = privateRoom?.verificationCode,
            privateRoomStrength = privateRoom?.strengthLabel,
            rooms = rooms,
            directPeer = directPeer,
            composer = composerState.text,
            peers = sortedPeers,
            messages = ConversationFilter.apply(routerState.messages, conversation),
            receivedFiles = ConversationFilter.applyFiles(routerState.files, conversation),
            courierQueueSize = routerState.courierQueueSize,
            courierEnabled = routerState.courierPolicy.enabled,
            courierRetentionMinutes = routerState.courierPolicy.retentionMinutes,
            blockedPeerIds = routerState.blockedPeerIds,
            blockedPeerCount = routerState.blockedPeerIds.size,
            pinnedRoomCount = rooms.count { it.isPinned },
            transportStatuses = routerState.statuses,
            diagnosticEvents = routerState.diagnostics
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(
            localPeerId = router.localPeerId,
            nickname = router.localName,
            localPublicKey = router.localPublicKey,
            channel = "lobby",
            privateRoomEnabled = false,
            privateRoomCode = null,
            privateRoomStrength = null,
            rooms = emptyList(),
            directPeer = null,
            composer = "",
            peers = emptyList(),
            messages = emptyList(),
            receivedFiles = emptyList(),
            courierQueueSize = 0,
            courierEnabled = CourierPolicy.Default.enabled,
            courierRetentionMinutes = CourierPolicy.Default.retentionMinutes,
            blockedPeerIds = emptySet(),
            blockedPeerCount = 0,
            pinnedRoomCount = 0,
            transportStatuses = emptyList(),
            diagnosticEvents = emptyList()
        )
    )

    fun updateComposer(value: String) {
        composer.value = value.take(2_000)
    }

    fun updateChannel(value: String) {
        val sanitized = ChatCommandParser.sanitizeChannel(value)
        channel.value = sanitized
        directPeerId.value = null
        rememberRoom(sanitized)
        markRoomRead(sanitized)
    }

    fun selectRoom(room: String) {
        val sanitized = ChatCommandParser.sanitizeChannel(room)
        channel.value = sanitized
        directPeerId.value = null
        rememberRoom(sanitized)
        markRoomRead(sanitized)
    }

    fun toggleRoomPinned(room: String) {
        val sanitized = ChatCommandParser.sanitizeChannel(room)
        rememberRoom(sanitized)
        val next = if (sanitized in pinnedRooms.value) {
            pinnedRooms.value - sanitized
        } else {
            pinnedRooms.value + sanitized
        }
        pinnedRooms.value = next
        roomPreferencesStore.savePinnedRooms(next)
    }

    fun moveRoomEarlier(room: String) {
        moveRoom(room, -1)
    }

    fun moveRoomLater(room: String) {
        moveRoom(room, 1)
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
            is ChatCommand.BlockPeer -> blockPeer(command.target)
            is ChatCommand.UnblockPeer -> unblockPeer(command.target)
            is ChatCommand.Action -> sendText("* ${router.localName} ${command.body}", input)
            is ChatCommand.LockRoom -> lockCurrentRoom(command.passphrase)
            is ChatCommand.RotateRoom -> rotateCurrentRoom(command.passphrase)
            ChatCommand.UnlockRoom -> unlockCurrentRoom()
            ChatCommand.ShowRoomCode -> showRoomCode()
            ChatCommand.ShowPeers -> showPeerNotice()
            ChatCommand.ShowBlockedPeers -> showBlockedPeerNotice()
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
                rememberRoom(channel.value)
                router.sendChannelMessage(channel = channel.value, body = body)
            }
        }
    }

    private fun joinRoom(room: String) {
        channel.value = room
        directPeerId.value = null
        rememberRoom(room)
        markRoomRead(room)
        router.appendLocalNotice(channel.value, "Joined #$room")
    }

    private fun leaveDirect() {
        directPeerId.value = null
        router.appendLocalNotice(channel.value, "Returned to #${channel.value}")
    }

    private fun lockCurrentRoom(passphrase: String) {
        directPeerId.value = null
        val locked = router.setRoomPassphrase(channel.value, passphrase)
        if (locked) {
            router.appendLocalNotice(channel.value, roomStatusNotice("Private room enabled"))
        } else {
            router.appendLocalNotice(channel.value, "Room passphrase was empty")
        }
    }

    private fun rotateCurrentRoom(passphrase: String) {
        directPeerId.value = null
        val rotated = router.setRoomPassphrase(channel.value, passphrase)
        if (rotated) {
            router.appendLocalNotice(channel.value, roomStatusNotice("Private room key rotated"))
        } else {
            router.appendLocalNotice(channel.value, "Room passphrase was empty")
        }
    }

    private fun unlockCurrentRoom() {
        directPeerId.value = null
        val unlocked = router.clearRoomPassphrase(channel.value)
        val body = if (unlocked) {
            "Private room disabled for #${channel.value}"
        } else {
            "No private-room key set for #${channel.value}"
        }
        router.appendLocalNotice(channel.value, body)
    }

    private fun showRoomCode() {
        directPeerId.value = null
        val status = router.privateRoomStatus(channel.value)
        val body = if (status == null) {
            "No private-room key set for #${channel.value}"
        } else {
            "Room code for #${channel.value}: ${status.verificationCode} / strength ${status.strengthLabel}"
        }
        router.appendLocalNotice(channel.value, body)
    }

    private fun roomStatusNotice(prefix: String): String {
        val status = router.privateRoomStatus(channel.value)
        return if (status == null) {
            "$prefix for #${channel.value}"
        } else {
            "$prefix for #${channel.value} / code ${status.verificationCode} / strength ${status.strengthLabel}"
        }
    }

    private fun sendCommandDirectMessage(command: ChatCommand.DirectMessage, originalInput: String) {
        val peer = resolvePeer(command.target)
        if (peer == null) {
            router.appendLocalNotice(activeConversationChannel(), "No peer matches ${command.target}")
            return
        }
        if (peer.isBlocked) {
            router.appendLocalNotice(activeConversationChannel(), "Peer is blocked: ${peer.name} (${peer.id.take(6)})")
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
            peers.joinToString { peer ->
                val suffix = if (peer.isBlocked) " blocked" else ""
                "${peer.name} (${peer.id.take(6)})$suffix"
            }
        }
        router.appendLocalNotice(activeConversationChannel(), body)
    }

    private fun showBlockedPeerNotice() {
        val state = uiState.value
        val peersById = state.peers.associateBy { it.id }
        val body = if (state.blockedPeerIds.isEmpty()) {
            "No blocked peers"
        } else {
            state.blockedPeerIds.sorted().joinToString { id ->
                peersById[id]?.let { peer -> "${peer.name} (${id.take(6)})" } ?: id.take(12)
            }
        }
        router.appendLocalNotice(activeConversationChannel(), body)
    }

    private fun blockPeer(target: String) {
        val peer = resolvePeer(target)
        if (peer == null) {
            router.appendLocalNotice(activeConversationChannel(), "No peer matches $target")
            return
        }
        if (router.blockPeer(peer.id)) {
            if (directPeerId.value == peer.id) directPeerId.value = null
            router.appendLocalNotice(activeConversationChannel(), "Blocked ${peer.name} (${peer.id.take(6)})")
        }
    }

    private fun unblockPeer(target: String) {
        val peerId = resolvePeer(target)?.id ?: resolveBlockedPeerId(target)
        if (peerId == null) {
            router.appendLocalNotice(activeConversationChannel(), "No blocked peer matches $target")
            return
        }
        router.unblockPeer(peerId)
        val name = uiState.value.peers.firstOrNull { it.id == peerId }?.name
        router.appendLocalNotice(activeConversationChannel(), "Unblocked ${name ?: peerId.take(12)}")
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

    private fun resolveBlockedPeerId(query: String): String? {
        val normalized = query.removePrefix("@").lowercase()
        return uiState.value.blockedPeerIds.firstOrNull { peerId ->
            peerId.equals(normalized, ignoreCase = true) ||
                peerId.lowercase().startsWith(normalized)
        }
    }

    private fun activeConversationChannel(): String {
        return uiState.value.directPeer?.let { "dm:${it.id}" } ?: channel.value
    }

    private fun rememberRoom(room: String) {
        val sanitized = ChatCommandParser.sanitizeChannel(room)
        val next = knownRooms.value + sanitized
        if (next != knownRooms.value) {
            knownRooms.value = next
            roomPreferencesStore.saveKnownRooms(next)
        }
        rememberRoomOrder(sanitized)
    }

    private fun markRoomRead(room: String) {
        roomReadAt.value = roomReadAt.value + (room to System.currentTimeMillis())
    }

    private fun rememberRoomOrder(room: String) {
        val sanitized = ChatCommandParser.sanitizeChannel(room)
        if (sanitized == "lobby" || sanitized.startsWith("dm:")) return
        if (sanitized in roomOrder.value) return
        val next = roomOrder.value + sanitized
        roomOrder.value = next
        roomPreferencesStore.saveRoomOrder(next)
    }

    private fun moveRoom(room: String, offset: Int) {
        val sanitized = ChatCommandParser.sanitizeChannel(room)
        if (sanitized == "lobby" || sanitized.startsWith("dm:")) return
        val currentRooms = uiState.value.rooms
            .map { it.channel }
            .filter { it != "lobby" && !it.startsWith("dm:") }
        val baseOrder = normalizeRoomOrder(roomOrder.value + currentRooms + knownRooms.value)
        val index = baseOrder.indexOf(sanitized)
        if (index < 0) return
        val nextIndex = (index + offset).coerceIn(baseOrder.indices)
        if (nextIndex == index) return
        val reordered = baseOrder.toMutableList()
        val moving = reordered.removeAt(index)
        reordered.add(nextIndex, moving)
        roomOrder.value = reordered
        roomPreferencesStore.saveRoomOrder(reordered)
    }

    fun retryDiscovery() {
        router.refreshTransports()
    }

    fun setCourierEnabled(enabled: Boolean) {
        val current = uiState.value
        router.updateCourierPolicy(
            CourierPolicy(
                enabled = enabled,
                retentionMinutes = current.courierRetentionMinutes,
                maxPacketsPerOrigin = router.courierPolicy.value.maxPacketsPerOrigin
            )
        )
    }

    fun setCourierRetentionMinutes(minutes: Int) {
        val current = uiState.value
        router.updateCourierPolicy(
            CourierPolicy(
                enabled = current.courierEnabled,
                retentionMinutes = minutes,
                maxPacketsPerOrigin = router.courierPolicy.value.maxPacketsPerOrigin
            )
        )
    }

    fun clearCourierQueue() {
        router.clearCourierQueue()
    }

    fun connectWifiPeer(peer: Peer) {
        router.connectWifiDirectPeer(peer.id)
    }

    fun selectDirectPeer(peer: Peer) {
        if (peer.isBlocked) {
            router.appendLocalNotice(activeConversationChannel(), "Peer is blocked: ${peer.name} (${peer.id.take(6)})")
            return
        }
        directPeerId.value = peer.id
    }

    fun clearDirectPeer() {
        directPeerId.value = null
    }

    fun resetRoomsAfterWipe() {
        channel.value = "lobby"
        directPeerId.value = null
        knownRooms.value = setOf("lobby")
        pinnedRooms.value = emptySet()
        roomReadAt.value = mapOf("lobby" to Long.MAX_VALUE)
    }

    fun trustPeer(peer: Peer) {
        router.trustPeer(peer.id)
    }

    fun forgetPeer(peer: Peer) {
        router.forgetTrustedPeer(peer.id)
    }

    fun blockPeer(peer: Peer) {
        if (router.blockPeer(peer.id)) {
            if (directPeerId.value == peer.id) directPeerId.value = null
            router.appendLocalNotice(activeConversationChannel(), "Blocked ${peer.name} (${peer.id.take(6)})")
        }
    }

    fun unblockPeer(peer: Peer) {
        router.unblockPeer(peer.id)
        router.appendLocalNotice(activeConversationChannel(), "Unblocked ${peer.name} (${peer.id.take(6)})")
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

private fun loadRoomSet(rooms: Set<String>, includeLobby: Boolean = false): Set<String> {
    val initialRooms = if (includeLobby) rooms + "lobby" else rooms
    return initialRooms
        .map(ChatCommandParser::sanitizeChannel)
        .filter { it.isNotBlank() && !it.startsWith("dm:") }
        .toSet()
}

private fun loadRoomList(rooms: List<String>): List<String> = normalizeRoomOrder(rooms)

private fun normalizeRoomOrder(rooms: Iterable<String>): List<String> {
    return rooms
        .map(ChatCommandParser::sanitizeChannel)
        .filter { it.isNotBlank() && it != "lobby" && !it.startsWith("dm:") }
        .distinct()
}

private const val COMMAND_HELP =
    "Commands: /join room, /lock passphrase, /code, /rotate passphrase, /unlock, /room, /msg peer text, /block peer, /unblock peer, /me action, /who."
