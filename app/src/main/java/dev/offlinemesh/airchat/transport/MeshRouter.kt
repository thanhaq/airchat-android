package dev.offlinemesh.airchat.transport

import dev.offlinemesh.airchat.crypto.CryptoBox
import dev.offlinemesh.airchat.crypto.EncryptedPayload
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import dev.offlinemesh.airchat.crypto.RoomCrypto
import dev.offlinemesh.airchat.crypto.RoomKey
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.CourierPacket
import dev.offlinemesh.airchat.model.CourierPolicy
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.DiagnosticEvent
import dev.offlinemesh.airchat.model.OutboxItem
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerTrustState
import dev.offlinemesh.airchat.model.PrivateRoomStatus
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.MeshPowerPolicy
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.model.TrustedPeer
import dev.offlinemesh.airchat.protocol.AckPayload
import dev.offlinemesh.airchat.protocol.AckStatus
import dev.offlinemesh.airchat.protocol.DirectEnvelope
import dev.offlinemesh.airchat.protocol.DirectKind
import dev.offlinemesh.airchat.protocol.DirectPayload
import dev.offlinemesh.airchat.protocol.FileChunk
import dev.offlinemesh.airchat.protocol.FileManifest
import dev.offlinemesh.airchat.protocol.FileTransferCodec
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.protocol.MeshPacketCodec
import dev.offlinemesh.airchat.protocol.MessageDeduper
import dev.offlinemesh.airchat.protocol.PacketGuard
import dev.offlinemesh.airchat.protocol.PacketGuardDecision
import dev.offlinemesh.airchat.protocol.PacketType
import dev.offlinemesh.airchat.protocol.RoomEncryptedPayload
import dev.offlinemesh.airchat.protocol.RoomEnvelope
import dev.offlinemesh.airchat.protocol.RoomEnvelopeKind
import dev.offlinemesh.airchat.store.ChatStore
import dev.offlinemesh.airchat.store.CourierStore
import dev.offlinemesh.airchat.store.InMemoryChatStore
import dev.offlinemesh.airchat.store.InMemoryCourierStore
import dev.offlinemesh.airchat.store.InMemoryPeerBlockStore
import dev.offlinemesh.airchat.store.InMemoryPeerTrustStore
import dev.offlinemesh.airchat.store.InMemoryReceivedFileStore
import dev.offlinemesh.airchat.store.PeerBlockStore
import dev.offlinemesh.airchat.store.PeerTrustStore
import dev.offlinemesh.airchat.store.ReceivedFileStore
import dev.offlinemesh.airchat.transport.wifidirect.WifiDirectTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeshRouter(
    private val localIdentity: MeshIdentity,
    private val chatStore: ChatStore = InMemoryChatStore(),
    private val peerTrustStore: PeerTrustStore = InMemoryPeerTrustStore(),
    private val peerBlockStore: PeerBlockStore = InMemoryPeerBlockStore(),
    private val receivedFileStore: ReceivedFileStore = InMemoryReceivedFileStore(),
    private val courierStore: CourierStore = InMemoryCourierStore(),
    private val transports: List<MeshTransport>,
    private val scope: CoroutineScope
) {
    private val deduper = MessageDeduper()
    private val peerIndex = MutableStateFlow<Map<String, Peer>>(emptyMap())
    private val visiblePeers = MutableStateFlow<List<Peer>>(emptyList())
    private val messageLog = MutableStateFlow(chatStore.loadMessages().takeLast(MAX_MESSAGES))
    private val outboxItems = MutableStateFlow(chatStore.loadOutbox())
    private val receivedFileLog = MutableStateFlow(receivedFileStore.loadReceivedFiles().takeLast(MAX_RECEIVED_FILES))
    private val trustedPeers = MutableStateFlow(peerTrustStore.loadTrustedPeers())
    private val blockedPeerSet = MutableStateFlow(peerBlockStore.loadBlockedPeers())
    private val statuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    private val diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val cryptoBox = CryptoBox()
    private val packetGuard = PacketGuard()
    private val fileAssemblies = mutableMapOf<String, PendingFileAssembly>()
    private val courierPolicyState = MutableStateFlow(courierStore.loadCourierPolicy().sanitized())
    private val powerPolicyState = MutableStateFlow(MeshPowerPolicy.Normal)
    private val courierPackets = loadInitialCourierPackets()
    private val courierCount = MutableStateFlow(courierPackets.size)
    private val roomKeys = mutableMapOf<String, RoomKey>()
    private val privateRoomStatusMap = MutableStateFlow<Map<String, PrivateRoomStatus>>(emptyMap())
    private val lockedRoomPackets = linkedMapOf<String, MeshPacket>()
    private val routerJobs = mutableListOf<Job>()
    private var started = false
    private var lastCourierFlushAt = 0L

    val localPeerId: String
        get() = localIdentity.peerId
    val localName: String
        get() = localIdentity.displayName
    val localPublicKey: String
        get() = localIdentity.publicKeyEncoded
    val peers: StateFlow<List<Peer>> = visiblePeers.asStateFlow()
    val messages: StateFlow<List<ChatMessage>> = messageLog.asStateFlow()
    val receivedFiles: StateFlow<List<ReceivedFile>> = receivedFileLog.asStateFlow()
    val transportStatuses: StateFlow<List<TransportStatus>> = statuses.asStateFlow()
    val diagnostics: StateFlow<List<DiagnosticEvent>> = diagnosticEvents.asStateFlow()
    val blockedPeerIds: StateFlow<Set<String>> = blockedPeerSet.asStateFlow()
    val courierQueueSize: StateFlow<Int> = courierCount.asStateFlow()
    val courierPolicy: StateFlow<CourierPolicy> = courierPolicyState.asStateFlow()
    val powerPolicy: StateFlow<MeshPowerPolicy> = powerPolicyState.asStateFlow()
    val privateRoomStatuses: StateFlow<Map<String, PrivateRoomStatus>> = privateRoomStatusMap.asStateFlow()

    fun start() {
        if (started) return
        started = true
        logEvent("router", "started with ${transports.size} transports")
        transports.forEach { transport ->
            routerJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.peers.collect { peers ->
                    updatePeerIndex(peers)
                    logEvent("peers", "${transport.name} sees ${peers.size} peers")
                    flushOutbox()
                    flushCourierQueue()
                }
            }
            routerJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.status.collect { status ->
                    val merged = statuses.value.filterNot { it.name == status.name } + status
                    statuses.value = merged.sortedBy { it.name }
                    logEvent("transport", "${status.name} ${status.state.name}: ${status.detail.take(MAX_DIAGNOSTIC_DETAIL)}")
                }
            }
            routerJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { event ->
                    when (event) {
                        is TransportEvent.PacketReceived -> handlePacket(event)
                    }
                }
            }
            routerJobs += scope.launch { transport.start() }
        }
        routerJobs += scope.launch {
            flushOutbox()
            flushCourierQueue()
        }
    }

    fun stop() {
        routerJobs.forEach { it.cancel() }
        routerJobs.clear()
        transports.forEach { transport ->
            scope.launch { transport.stop() }
        }
        started = false
        logEvent("router", "stopped")
    }

    fun refreshTransports() {
        logEvent("transport", "manual refresh requested")
        transports.forEach { transport ->
            scope.launch {
                transport.stop()
                transport.start()
            }
        }
    }

    fun updateCourierPolicy(policy: CourierPolicy) {
        val sanitized = policy.sanitized()
        courierPolicyState.value = sanitized
        courierStore.saveCourierPolicy(sanitized)
        applyCourierPolicyToQueue()
        logEvent(
            "courier",
            "policy ${if (sanitized.enabled) "enabled" else "disabled"} retention ${sanitized.retentionMinutes}m"
        )
    }

    fun updatePowerPolicy(policy: MeshPowerPolicy) {
        val sanitized = policy.sanitized()
        val previous = powerPolicyState.value
        if (previous == sanitized) return
        powerPolicyState.value = sanitized
        logEvent(
            "power",
            "mode ${sanitized.mode.name.lowercase()} relay ttl ${sanitized.maxRelayTtl} " +
                "courier flush ${sanitized.courierFlushIntervalMs / 1_000}s storage ${sanitized.storeCourierPackets}"
        )
    }

    fun clearCourierQueue() {
        val cleared = courierPackets.size
        courierPackets.clear()
        persistCourierQueue()
        if (cleared > 0) {
            logEvent("courier", "cleared $cleared packets by user")
        }
    }

    fun blockPeer(peerId: String): Boolean {
        val normalized = peerId.trim()
        if (normalized.isBlank() || normalized == localPeerId) return false
        peerBlockStore.blockPeer(normalized)
        blockedPeerSet.value = peerBlockStore.loadBlockedPeers()
        dropOutboxForBlockedPeer(normalized)
        refreshVisiblePeers()
        logEvent("block", "blocked ${normalized.take(6)}")
        return true
    }

    fun unblockPeer(peerId: String): Boolean {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return false
        peerBlockStore.unblockPeer(normalized)
        blockedPeerSet.value = peerBlockStore.loadBlockedPeers()
        refreshVisiblePeers()
        logEvent("block", "unblocked ${normalized.take(6)}")
        return true
    }

    fun clearLocalState() {
        deduper.clear()
        packetGuard.clear()
        peerIndex.value = emptyMap()
        visiblePeers.value = emptyList()
        messageLog.value = emptyList()
        outboxItems.value = emptyList()
        receivedFileLog.value = emptyList()
        fileAssemblies.clear()
        courierPackets.clear()
        courierCount.value = 0
        roomKeys.values.forEach { it.bytes.fill(0) }
        roomKeys.clear()
        publishPrivateRoomState()
        lockedRoomPackets.clear()
        courierStore.clear()
        chatStore.clear()
        receivedFileStore.clear()
        trustedPeers.value = emptyMap()
        peerTrustStore.clear()
        blockedPeerSet.value = emptySet()
        peerBlockStore.clear()
        diagnosticEvents.value = emptyList()
        logEvent("privacy", "local state wiped")
    }

    fun trustPeer(peerId: String): Boolean {
        if (isPeerBlocked(peerId)) return false
        val peer = peerIndex.value[peerId] ?: return false
        val publicKey = peer.publicKey ?: return false
        val trustedPeer = TrustedPeer(
            peerId = peer.id,
            displayName = peer.name,
            publicKey = publicKey,
            trustedAt = System.currentTimeMillis()
        )
        peerTrustStore.trustPeer(trustedPeer)
        trustedPeers.value = peerTrustStore.loadTrustedPeers()
        refreshVisiblePeers()
        return true
    }

    fun appendLocalNotice(channel: String, body: String) {
        appendSyntheticMessage(
            id = "notice:${UUID.randomUUID()}",
            channel = channel,
            senderId = localPeerId,
            senderName = "AirChat",
            body = body,
            state = DeliveryState.Verified,
            isLocal = true
        )
    }

    fun setRoomPassphrase(channel: String, passphrase: String): Boolean {
        val normalized = channel.trim()
        val trimmedPassphrase = passphrase.trim()
        if (normalized.isBlank() || trimmedPassphrase.isBlank()) return false
        roomKeys.remove(normalized)?.bytes?.fill(0)
        roomKeys[normalized] = RoomCrypto.deriveRoomKey(normalized, trimmedPassphrase)
        publishPrivateRoomState()
        logEvent("room", "private key set for #$normalized")
        scope.launch {
            unlockBufferedRoomPackets(normalized)
        }
        return true
    }

    fun clearRoomPassphrase(channel: String): Boolean {
        val removed = roomKeys.remove(channel.trim())
        removed?.bytes?.fill(0)
        publishPrivateRoomState()
        if (removed != null) logEvent("room", "private key cleared for #${channel.trim()}")
        return removed != null
    }

    fun privateRoomStatus(channel: String): PrivateRoomStatus? =
        privateRoomStatusMap.value[channel.trim()]

    fun forgetTrustedPeer(peerId: String) {
        peerTrustStore.forgetPeer(peerId)
        trustedPeers.value = peerTrustStore.loadTrustedPeers()
        refreshVisiblePeers()
    }

    suspend fun sendChannelMessage(channel: String, body: String) {
        val packet = roomKeys[channel]?.let { roomKey ->
            encryptedRoomPacket(
                channel = channel,
                roomKey = roomKey,
                envelope = RoomEnvelope(
                    kind = RoomEnvelopeKind.Text,
                    body = body
                )
            )
        } ?: signedPacket(
            type = PacketType.Chat,
            channel = channel,
            payload = body,
            ttl = DEFAULT_TTL
        )
        deduper.remember(packet.id)
        val delivered = broadcastPacket(packet)
        logEvent(
            "message",
            "room packet ${packet.type.name} in #$channel ${if (delivered) "broadcast" else "queued"}"
        )
        appendMessage(
            packet = packet,
            state = if (delivered) DeliveryState.Sent else DeliveryState.Pending,
            hopCount = 0,
            isLocal = true,
            bodyOverride = body
        )
        if (!delivered) queueOutbox(packet, targetPeerId = null)
    }

    suspend fun sendDirectMessage(peerId: String, body: String): Boolean {
        if (isPeerBlocked(peerId)) {
            logEvent("block", "blocked direct send to ${peerId.take(6)}")
            return false
        }
        val peer = peerIndex.value[peerId] ?: return false
        if (trustStateFor(peer) == PeerTrustState.KeyChanged) return false
        val peerPublicKey = peer.publicKey ?: return false
        val packetId = UUID.randomUUID().toString()
        val packet = encryptedDirectPacket(
            peerId = peerId,
            peerPublicKey = peerPublicKey,
            id = packetId,
            envelope = DirectEnvelope(
                kind = DirectKind.Text,
                body = body
            )
        )
        deduper.remember(packet.id)
        val delivered = sendTargetedOrBroadcast(peerId, packet)
        if (!delivered) queueOutbox(packet, targetPeerId = peerId)
        logEvent("message", "direct text to ${peerId.take(6)} ${if (delivered) "sent" else "queued"}")
        appendMessage(
            packet = packet,
            state = if (delivered) DeliveryState.Sent else DeliveryState.Pending,
            hopCount = 0,
            isLocal = true,
            bodyOverride = body
        )
        return true
    }

    suspend fun sendDirectFile(
        peerId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Boolean {
        if (isPeerBlocked(peerId)) {
            logEvent("block", "blocked direct file to ${peerId.take(6)}")
            return false
        }
        val peer = peerIndex.value[peerId] ?: return false
        if (trustStateFor(peer) == PeerTrustState.KeyChanged) return false
        val peerPublicKey = peer.publicKey ?: return false
        val plan = FileTransferCodec.createPlan(
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes
        )
        val manifestPacket = encryptedDirectPacket(
            peerId = peerId,
            peerPublicKey = peerPublicKey,
            id = "dfile:${plan.manifest.transferId}:manifest",
            envelope = DirectEnvelope(
                kind = DirectKind.FileManifest,
                body = MeshPacketCodec.encodePayload(plan.manifest)
            )
        )
        val chunkPackets = plan.chunks.map { chunk ->
            encryptedDirectPacket(
                peerId = peerId,
                peerPublicKey = peerPublicKey,
                id = "dfile:${chunk.transferId}:chunk:${chunk.index}",
                envelope = DirectEnvelope(
                    kind = DirectKind.FileChunk,
                    body = MeshPacketCodec.encodePayload(chunk)
                )
            )
        }
        var delivered = true
        (listOf(manifestPacket) + chunkPackets).forEach { packet ->
            deduper.remember(packet.id)
            if (!sendTargetedOrBroadcast(peerId, packet)) {
                delivered = false
                queueOutbox(packet, targetPeerId = peerId)
            }
        }
        appendMessage(
            packet = manifestPacket,
            state = if (delivered) DeliveryState.Sent else DeliveryState.Pending,
            hopCount = 0,
            isLocal = true,
            bodyOverride = "Sent encrypted file: ${plan.manifest.fileName} (${formatBytes(plan.manifest.totalBytes)})"
        )
        logEvent("file", "direct file to ${peerId.take(6)} ${if (delivered) "sent" else "queued"}")
        return delivered
    }

    suspend fun sendChannelFile(
        channel: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Boolean {
        val plan = FileTransferCodec.createPlan(
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes
        )
        val roomKey = roomKeys[channel]
        val manifestPacket = if (roomKey != null) {
            encryptedRoomPacket(
                id = "efile:${plan.manifest.transferId}:manifest",
                channel = channel,
                roomKey = roomKey,
                envelope = RoomEnvelope(
                    kind = RoomEnvelopeKind.FileManifest,
                    body = MeshPacketCodec.encodePayload(plan.manifest)
                )
            )
        } else {
            signedPacket(
                id = "file:${plan.manifest.transferId}:manifest",
                type = PacketType.FileManifest,
                channel = channel,
                payload = MeshPacketCodec.encodePayload(plan.manifest),
                ttl = DEFAULT_TTL
            )
        }
        val chunkPackets = plan.chunks.map { chunk ->
            if (roomKey != null) {
                encryptedRoomPacket(
                    id = "efile:${chunk.transferId}:chunk:${chunk.index}",
                    channel = channel,
                    roomKey = roomKey,
                    envelope = RoomEnvelope(
                        kind = RoomEnvelopeKind.FileChunk,
                        body = MeshPacketCodec.encodePayload(chunk)
                    )
                )
            } else {
                signedPacket(
                    id = "file:${chunk.transferId}:chunk:${chunk.index}",
                    type = PacketType.FileChunk,
                    channel = channel,
                    payload = MeshPacketCodec.encodePayload(chunk),
                    ttl = DEFAULT_TTL
                )
            }
        }
        val packets = listOf(manifestPacket) + chunkPackets
        var delivered = true
        packets.forEach { packet ->
            deduper.remember(packet.id)
            if (!broadcastPacket(packet)) {
                delivered = false
                queueOutbox(packet, targetPeerId = null)
            }
        }
        appendMessage(
            packet = manifestPacket,
            state = if (delivered) DeliveryState.Sent else DeliveryState.Pending,
            hopCount = 0,
            isLocal = true,
            bodyOverride = if (roomKey != null) {
                "Sent private-room file: ${plan.manifest.fileName} (${formatBytes(plan.manifest.totalBytes)})"
            } else {
                "Sent file: ${plan.manifest.fileName} (${formatBytes(plan.manifest.totalBytes)})"
            }
        )
        logEvent("file", "room file in #$channel ${if (delivered) "broadcast" else "queued"}")
        return delivered
    }

    fun connectWifiDirectPeer(peerId: String) {
        val wifiDirect = transports.filterIsInstance<WifiDirectTransport>().firstOrNull() ?: return
        wifiDirect.connect(peerId)
    }

    private suspend fun handlePacket(event: TransportEvent.PacketReceived) {
        val packet = event.packet
        when (val decision = packetGuard.inspect(packet)) {
            PacketGuardDecision.Accepted -> Unit
            PacketGuardDecision.RateLimited -> {
                logEvent("guard", "rate-limited ${packet.type.name} from ${packet.originId.take(6)}")
                return
            }

            is PacketGuardDecision.Rejected -> {
                logEvent("guard", "${decision.reason} for ${packet.type.name} from ${packet.originId.take(6)}")
                return
            }
        }
        if (!deduper.remember(packet.id)) {
            logEvent("packet", "duplicate ${packet.type.name} ignored from ${packet.originId.take(6)}")
            return
        }
        if (packet.originId == localPeerId) return

        event.remotePeer?.let { peer ->
            updatePeerIndex(listOf(peer))
        }
        if (isPeerBlocked(packet.originId)) {
            logEvent("block", "dropped ${packet.type.name} from ${packet.originId.take(6)}")
            return
        }

        val verified = packet.signature?.let {
            IdentityStore.verify(
                publicKeyEncoded = packet.originPublicKey,
                bytes = MeshPacketCodec.signingBytes(packet),
                signatureEncoded = it
            )
        } ?: false
        logEvent(
            "packet",
            "received ${packet.type.name} on ${event.transportName} for ${channelLabel(packet.channel)} " +
                if (verified) "verified" else "unverified"
        )

        when (packet.type) {
            PacketType.Chat -> {
                appendMessage(
                    packet = packet,
                    state = if (verified) DeliveryState.Verified else DeliveryState.Unverified,
                    hopCount = DEFAULT_TTL - packet.ttl,
                    isLocal = false
                )
                sendAckFor(packet, verified)
            }

            PacketType.RoomEncrypted -> handleRoomEncryptedPacket(packet, verified)
            PacketType.Direct -> handleDirectPacket(packet, verified)
            PacketType.FileManifest -> handleFileManifest(packet, verified)
            PacketType.FileChunk -> handleFileChunk(packet, verified)
            PacketType.Ack -> handleAckPacket(packet, verified)
            PacketType.Hello -> Unit
        }

        relayOrQueueCourier(packet, verified)
    }

    private fun updatePeerIndex(peers: List<Peer>) {
        peerIndex.value = peerIndex.value + peers.associateBy { it.id }
        refreshVisiblePeers()
    }

    private fun refreshVisiblePeers() {
        val blocked = blockedPeerSet.value
        visiblePeers.value = peerIndex.value.values
            .filterNot { it.id == localPeerId }
            .map { peer ->
                peer.copy(
                    trustState = trustStateFor(peer),
                    isBlocked = peer.id in blocked
                )
            }
    }

    private fun isPeerBlocked(peerId: String): Boolean =
        peerId in blockedPeerSet.value

    private fun publishPrivateRoomState() {
        privateRoomStatusMap.value = roomKeys.mapValues { (channel, roomKey) ->
            PrivateRoomStatus(
                channel = channel,
                verificationCode = roomKey.verificationCode,
                strengthLabel = roomKey.strength.label
            )
        }
    }

    private fun trustStateFor(peer: Peer): PeerTrustState {
        val trusted = trustedPeers.value[peer.id] ?: return PeerTrustState.Unknown
        val publicKey = peer.publicKey ?: return PeerTrustState.Unknown
        return if (trusted.publicKey == publicKey) PeerTrustState.Trusted else PeerTrustState.KeyChanged
    }

    private suspend fun handleRoomEncryptedPacket(
        packet: MeshPacket,
        verified: Boolean,
        allowBuffer: Boolean = true
    ): Boolean {
        if (!verified) return false
        val roomKey = roomKeys[packet.channel]
        val encryptedPayload = MeshPacketCodec.decodePayload<RoomEncryptedPayload>(packet.payload) ?: return false
        if (roomKey == null) {
            if (allowBuffer) bufferLockedRoomPacket(packet)
            return false
        }
        val plaintext = RoomCrypto.decrypt(
            roomKey = roomKey,
            packetId = packet.id,
            payload = encryptedPayload
        ) ?: run {
            if (allowBuffer) bufferLockedRoomPacket(packet)
            return false
        }
        lockedRoomPackets.remove(packet.id)
        val envelope = MeshPacketCodec.decodePayload<RoomEnvelope>(String(plaintext, Charsets.UTF_8))
        if (envelope == null) {
            appendMessage(
                packet = packet,
                state = DeliveryState.Verified,
                hopCount = DEFAULT_TTL - packet.ttl,
                isLocal = false,
                bodyOverride = String(plaintext, Charsets.UTF_8)
            )
            sendAckFor(packet, verified = true)
            return true
        }
        when (envelope.kind) {
            RoomEnvelopeKind.Text -> {
                appendMessage(
                    packet = packet,
                    state = DeliveryState.Verified,
                    hopCount = DEFAULT_TTL - packet.ttl,
                    isLocal = false,
                    bodyOverride = envelope.body
                )
                sendAckFor(packet, verified = true)
            }

            RoomEnvelopeKind.FileManifest -> {
                val manifest = MeshPacketCodec.decodePayload<FileManifest>(envelope.body) ?: return false
                acceptFileManifest(
                    transferId = manifest.transferId,
                    manifest = manifest,
                    senderId = packet.originId,
                    senderName = packet.originName,
                    channel = packet.channel,
                    verified = true
                )
            }

            RoomEnvelopeKind.FileChunk -> {
                val chunk = MeshPacketCodec.decodePayload<FileChunk>(envelope.body) ?: return false
                acceptFileChunk(
                    transferId = chunk.transferId,
                    chunk = chunk,
                    senderId = packet.originId,
                    senderName = packet.originName,
                    channel = packet.channel,
                    verified = true
                )
            }
        }
        return true
    }

    private fun bufferLockedRoomPacket(packet: MeshPacket) {
        lockedRoomPackets[packet.id] = packet
        while (lockedRoomPackets.size > MAX_LOCKED_ROOM_PACKETS) {
            val eldest = lockedRoomPackets.keys.firstOrNull() ?: break
            lockedRoomPackets.remove(eldest)
        }
        appendMessage(
            packet = packet,
            state = DeliveryState.Locked,
            hopCount = DEFAULT_TTL - packet.ttl,
            isLocal = false,
            bodyOverride = "Encrypted room message. Use /lock passphrase to unlock this room."
        )
    }

    private suspend fun unlockBufferedRoomPackets(channel: String) {
        val candidates = lockedRoomPackets.values.filter { it.channel == channel }
        candidates.forEach { packet ->
            handleRoomEncryptedPacket(packet, verified = true, allowBuffer = false)
        }
    }

    private suspend fun handleDirectPacket(packet: MeshPacket, verified: Boolean) {
        val payload = MeshPacketCodec.decodePayload<DirectPayload>(packet.payload) ?: return
        if (payload.recipientId != localPeerId) return
        val plaintext = runCatching {
            val decrypted = cryptoBox.decryptFor(
                recipientPrivateKey = localIdentity.privateKey(),
                recipientPublicKey = IdentityStore.decodePublicKey(localIdentity.publicKeyEncoded),
                senderEphemeralPublicKey = IdentityStore.decodePublicKey(payload.ephemeralPublicKey),
                encryptedPayload = EncryptedPayload(
                    ephemeralPublicKey = payload.ephemeralPublicKey,
                    nonce = payload.nonce,
                    ciphertext = payload.ciphertext
                ),
                aad = directAad(packet.id, localPeerId)
            )
            String(decrypted, Charsets.UTF_8)
        }.getOrNull() ?: return
        val envelope = MeshPacketCodec.decodePayload<DirectEnvelope>(plaintext)
        if (envelope != null) {
            handleDirectEnvelope(packet, verified, envelope)
            return
        }
        appendMessage(
            packet = packet,
            state = if (verified) DeliveryState.Verified else DeliveryState.Unverified,
            hopCount = DEFAULT_TTL - packet.ttl,
            isLocal = false,
            bodyOverride = plaintext,
            channelOverride = "dm:${packet.originId}"
        )
        sendAckFor(packet, verified)
    }

    private suspend fun handleDirectEnvelope(packet: MeshPacket, verified: Boolean, envelope: DirectEnvelope) {
        when (envelope.kind) {
            DirectKind.Text -> {
                appendMessage(
                    packet = packet,
                    state = if (verified) DeliveryState.Verified else DeliveryState.Unverified,
                    hopCount = DEFAULT_TTL - packet.ttl,
                    isLocal = false,
                    bodyOverride = envelope.body,
                    channelOverride = "dm:${packet.originId}"
                )
                sendAckFor(packet, verified)
            }

            DirectKind.FileManifest -> {
                val manifest = MeshPacketCodec.decodePayload<FileManifest>(envelope.body) ?: return
                acceptFileManifest(
                    transferId = manifest.transferId,
                    manifest = manifest,
                    senderId = packet.originId,
                    senderName = packet.originName,
                    channel = "dm:${packet.originId}",
                    verified = verified
                )
            }

            DirectKind.FileChunk -> {
                val chunk = MeshPacketCodec.decodePayload<FileChunk>(envelope.body) ?: return
                acceptFileChunk(
                    transferId = chunk.transferId,
                    chunk = chunk,
                    senderId = packet.originId,
                    senderName = packet.originName,
                    channel = "dm:${packet.originId}",
                    verified = verified
                )
            }
        }
    }

    private suspend fun sendAckFor(packet: MeshPacket, verified: Boolean) {
        if (!verified) return
        val status = if (verified) AckStatus.Verified else AckStatus.Unverified
        val ack = signedPacket(
            id = "ack:${packet.id}:$localPeerId",
            type = PacketType.Ack,
            channel = packet.channel,
            payload = MeshPacketCodec.encodePayload(
                AckPayload(
                    packetId = packet.id,
                    receivedAt = System.currentTimeMillis(),
                    status = status
                )
            ),
            ttl = DEFAULT_TTL
        )
        deduper.remember(ack.id)
        sendTargetedOrBroadcast(packet.originId, ack)
        logEvent("ack", "sent for ${packet.id.take(8)} to ${packet.originId.take(6)}")
    }

    private fun handleAckPacket(packet: MeshPacket, verified: Boolean) {
        if (!verified) return
        val ack = MeshPacketCodec.decodePayload<AckPayload>(packet.payload) ?: return
        val message = messageLog.value.firstOrNull { candidate ->
            candidate.id == ack.packetId &&
                candidate.isLocal &&
                candidate.senderId == localPeerId &&
                isAckAuthorized(candidate, packet.originId)
        } ?: return
        if (message.state == DeliveryState.Pending || message.state == DeliveryState.Sent) {
            updateMessageState(message.id, DeliveryState.Received)
            logEvent("ack", "received for ${ack.packetId.take(8)} from ${packet.originId.take(6)}")
        }
    }

    private fun isAckAuthorized(message: ChatMessage, ackOriginId: String): Boolean {
        if (!message.channel.startsWith(DIRECT_CHANNEL_PREFIX)) return true
        return message.channel.removePrefix(DIRECT_CHANNEL_PREFIX) == ackOriginId
    }

    private fun handleFileManifest(packet: MeshPacket, verified: Boolean) {
        if (packet.channel.startsWith(DIRECT_CHANNEL_PREFIX)) return
        val manifest = MeshPacketCodec.decodePayload<FileManifest>(packet.payload) ?: return
        acceptFileManifest(
            transferId = manifest.transferId,
            manifest = manifest,
            senderId = packet.originId,
            senderName = packet.originName,
            channel = packet.channel,
            verified = verified
        )
    }

    private fun handleFileChunk(packet: MeshPacket, verified: Boolean) {
        if (packet.channel.startsWith(DIRECT_CHANNEL_PREFIX)) return
        val chunk = MeshPacketCodec.decodePayload<FileChunk>(packet.payload) ?: return
        acceptFileChunk(
            transferId = chunk.transferId,
            chunk = chunk,
            senderId = packet.originId,
            senderName = packet.originName,
            channel = packet.channel,
            verified = verified
        )
    }

    private fun acceptFileManifest(
        transferId: String,
        manifest: FileManifest,
        senderId: String,
        senderName: String,
        channel: String,
        verified: Boolean
    ) {
        val assembly = fileAssemblies.getOrPut(transferId) {
            PendingFileAssembly(
                senderId = senderId,
                senderName = senderName,
                channel = channel
            )
        }
        assembly.manifest = manifest
        assembly.verified = assembly.verified && verified
        tryCompleteFileTransfer(transferId)
    }

    private fun acceptFileChunk(
        transferId: String,
        chunk: FileChunk,
        senderId: String,
        senderName: String,
        channel: String,
        verified: Boolean
    ) {
        val assembly = fileAssemblies.getOrPut(transferId) {
            PendingFileAssembly(
                senderId = senderId,
                senderName = senderName,
                channel = channel
            )
        }
        assembly.chunks[chunk.index] = chunk
        assembly.verified = assembly.verified && verified
        tryCompleteFileTransfer(transferId)
    }

    private fun tryCompleteFileTransfer(transferId: String) {
        val assembly = fileAssemblies[transferId] ?: return
        val manifest = assembly.manifest ?: return
        if (assembly.chunks.size < manifest.totalChunks) return
        val bytes = FileTransferCodec.reassemble(manifest, assembly.chunks.values.toList()) ?: return
        val receivedFile = ReceivedFile(
            id = transferId,
            fileName = manifest.fileName,
            mimeType = manifest.mimeType,
            totalBytes = manifest.totalBytes,
            sha256 = manifest.sha256,
            senderId = assembly.senderId,
            senderName = assembly.senderName,
            channel = assembly.channel,
            receivedAt = System.currentTimeMillis(),
            bytes = bytes
        )
        val updatedFiles = (receivedFileLog.value.filterNot { it.id == transferId } + receivedFile)
            .takeLast(MAX_RECEIVED_FILES)
        receivedFileLog.value = updatedFiles
        receivedFileStore.saveReceivedFiles(updatedFiles)
        fileAssemblies.remove(transferId)
        logEvent(
            "file",
            "received ${formatBytes(manifest.totalBytes)} on ${channelLabel(assembly.channel)} " +
                if (assembly.verified) "verified" else "unverified"
        )
        appendSyntheticMessage(
            id = "file:$transferId:complete",
            channel = assembly.channel,
            senderId = assembly.senderId,
            senderName = assembly.senderName,
            body = "Received file: ${manifest.fileName} (${formatBytes(manifest.totalBytes)})",
            state = if (assembly.verified) DeliveryState.Verified else DeliveryState.Unverified,
            isLocal = false
        )
    }

    private fun appendMessage(
        packet: MeshPacket,
        state: DeliveryState,
        hopCount: Int,
        isLocal: Boolean,
        bodyOverride: String? = null,
        channelOverride: String? = null
    ) {
        val message = ChatMessage(
            id = packet.id,
            channel = channelOverride ?: packet.channel,
            senderId = packet.originId,
            senderName = packet.originName,
            body = bodyOverride ?: packet.payload,
            createdAt = packet.createdAt,
            state = state,
            hopCount = hopCount.coerceAtLeast(0),
            isLocal = isLocal
        )
        messageLog.value = (messageLog.value.filterNot { it.id == message.id } + message).takeLast(MAX_MESSAGES)
        chatStore.saveMessages(messageLog.value)
    }

    private fun updateMessageState(messageId: String, state: DeliveryState) {
        val updated = messageLog.value.map { message ->
            if (message.id == messageId) message.copy(state = state) else message
        }
        messageLog.value = updated
        chatStore.saveMessages(updated)
    }

    private fun appendSyntheticMessage(
        id: String,
        channel: String,
        senderId: String,
        senderName: String,
        body: String,
        state: DeliveryState,
        isLocal: Boolean
    ) {
        val message = ChatMessage(
            id = id,
            channel = channel,
            senderId = senderId,
            senderName = senderName,
            body = body,
            createdAt = System.currentTimeMillis(),
            state = state,
            isLocal = isLocal
        )
        messageLog.value = (messageLog.value.filterNot { it.id == id } + message).takeLast(MAX_MESSAGES)
        chatStore.saveMessages(messageLog.value)
    }

    private suspend fun broadcastPacket(packet: MeshPacket): Boolean {
        var delivered = false
        for (transport in transports) {
            if (transport.broadcast(packet)) delivered = true
        }
        return delivered
    }

    private suspend fun relayOrQueueCourier(packet: MeshPacket, verified: Boolean) {
        if (!shouldRelay(packet, verified)) return
        val powerPolicy = powerPolicyState.value
        val relayed = packet.copy(
            ttl = (packet.ttl - 1).coerceAtMost(powerPolicy.maxRelayTtl),
            path = packet.path + localPeerId
        )
        if (relayed.ttl <= 0) return
        if (!broadcastPacket(relayed)) {
            queueCourierPacket(relayed)
        } else {
            val mode = powerPolicy.mode.name.lowercase()
            logEvent("relay", "relayed ${packet.type.name} from ${packet.originId.take(6)} ttl ${relayed.ttl} power $mode")
        }
    }

    private fun shouldRelay(packet: MeshPacket, verified: Boolean): Boolean {
        return verified &&
            packet.type != PacketType.Hello &&
            packet.ttl > 0 &&
            packet.originId != localPeerId &&
            localPeerId !in packet.path
    }

    private fun queueCourierPacket(packet: MeshPacket) {
        pruneCourierQueue()
        val policy = courierPolicyState.value
        if (!policy.enabled) {
            logEvent("courier", "relay disabled; dropped ${packet.type.name} from ${packet.originId.take(6)}")
            return
        }
        val powerPolicy = powerPolicyState.value
        if (!powerPolicy.storeCourierPackets) {
            logEvent("power", "courier storage paused; dropped ${packet.type.name} from ${packet.originId.take(6)}")
            return
        }
        if (packet.ttl <= 0 || packet.originId == localPeerId) return
        val now = System.currentTimeMillis()
        courierPackets.remove(packet.id)
        courierPackets[packet.id] = CourierPacket(
            packet = packet,
            expiresAt = now + policy.retentionMillis
        )
        while (courierPackets.size > MAX_COURIER_PACKETS) {
            val eldest = courierPackets.keys.firstOrNull() ?: break
            courierPackets.remove(eldest)
        }
        persistCourierQueue()
        logEvent("courier", "queued ${packet.type.name} from ${packet.originId.take(6)} ttl ${packet.ttl}")
    }

    private suspend fun flushCourierQueue() {
        pruneCourierQueue()
        if (courierPackets.isEmpty()) return
        val powerPolicy = powerPolicyState.value
        val now = System.currentTimeMillis()
        if (powerPolicy.courierFlushIntervalMs > 0L &&
            now - lastCourierFlushAt < powerPolicy.courierFlushIntervalMs
        ) {
            return
        }
        lastCourierFlushAt = now
        val retainedBeforeFlush = courierPackets.size
        val retained = linkedMapOf<String, CourierPacket>()
        courierPackets.values.forEach { item ->
            if (!broadcastPacket(item.packet)) {
                retained[item.packet.id] = item
            }
        }
        courierPackets.clear()
        courierPackets.putAll(retained)
        persistCourierQueue()
        if (retained.size != retainedBeforeFlush) {
            logEvent("courier", "flushed ${retainedBeforeFlush - retained.size} packets, retained ${retained.size}")
        }
    }

    private fun pruneCourierQueue() {
        if (courierPackets.isEmpty()) return
        val now = System.currentTimeMillis()
        val expired = courierPackets.filterValues { it.expiresAt <= now }.keys
        if (expired.isEmpty()) return
        expired.forEach(courierPackets::remove)
        persistCourierQueue()
        logEvent("courier", "expired ${expired.size} packets")
    }

    private fun persistCourierQueue() {
        val packets = courierPackets.values.toList()
        courierCount.value = packets.size
        courierStore.saveCourierPackets(packets)
    }

    private fun loadInitialCourierPackets(): LinkedHashMap<String, CourierPacket> {
        val now = System.currentTimeMillis()
        val policy = courierPolicyState.value
        val stored = courierStore.loadCourierPackets()
        var changed = false
        val packets = if (!policy.enabled) {
            emptyList()
        } else {
            val maxExpiresAt = now + policy.retentionMillis
            stored
                .filter { item ->
                    item.expiresAt > now &&
                        item.packet.ttl > 0 &&
                        item.packet.originId != localPeerId &&
                        localPeerId in item.packet.path
                }
                .map { item ->
                    if (item.expiresAt > maxExpiresAt) {
                        changed = true
                        item.copy(expiresAt = maxExpiresAt)
                    } else {
                        item
                    }
                }
                .takeLast(MAX_COURIER_PACKETS)
        }
        if (packets.size != stored.size || changed) {
            courierStore.saveCourierPackets(packets)
        }
        return linkedMapOf<String, CourierPacket>().apply {
            packets.forEach { item -> put(item.packet.id, item) }
        }
    }

    private fun applyCourierPolicyToQueue() {
        if (courierPackets.isEmpty()) return
        val policy = courierPolicyState.value
        if (!policy.enabled) {
            val cleared = courierPackets.size
            courierPackets.clear()
            persistCourierQueue()
            logEvent("courier", "cleared $cleared packets after disabling relay")
            return
        }
        val maxExpiresAt = System.currentTimeMillis() + policy.retentionMillis
        var changed = false
        val clamped = courierPackets.mapValues { (_, item) ->
            if (item.expiresAt > maxExpiresAt) {
                changed = true
                item.copy(expiresAt = maxExpiresAt)
            } else {
                item
            }
        }
        if (changed) {
            courierPackets.clear()
            courierPackets.putAll(clamped)
            persistCourierQueue()
        }
        pruneCourierQueue()
    }

    private fun queueOutbox(packet: MeshPacket, targetPeerId: String?) {
        if (targetPeerId != null && isPeerBlocked(targetPeerId)) {
            logEvent("block", "dropped queued packet to ${targetPeerId.take(6)}")
            return
        }
        val now = System.currentTimeMillis()
        val item = OutboxItem(
            id = packet.id,
            packet = packet,
            targetPeerId = targetPeerId,
            createdAt = now,
            attempts = 0,
            nextAttemptAt = now
        )
        val updated = (outboxItems.value.filterNot { it.id == item.id } + item).takeLast(MAX_OUTBOX_ITEMS)
        outboxItems.value = updated
        chatStore.saveOutbox(updated)
        logEvent("outbox", "queued ${packet.type.name} for ${targetPeerId?.take(6) ?: "broadcast"}")
    }

    private suspend fun flushOutbox() {
        val now = System.currentTimeMillis()
        val retained = mutableListOf<OutboxItem>()
        var changed = false
        outboxItems.value.forEach { item ->
            if (item.targetPeerId != null && isPeerBlocked(item.targetPeerId)) {
                changed = true
                logEvent("block", "dropped outbox packet to ${item.targetPeerId.take(6)}")
                return@forEach
            }
            if (item.nextAttemptAt > now) {
                retained += item
                return@forEach
            }
            val delivered = if (item.targetPeerId == null) {
                broadcastPacket(item.packet)
            } else {
                sendTargetedOrBroadcast(item.targetPeerId, item.packet)
            }
            if (delivered) {
                changed = true
                updateMessageState(item.id, DeliveryState.Sent)
                logEvent("outbox", "sent ${item.packet.type.name} after ${item.attempts} attempts")
            } else if (now - item.createdAt < OUTBOX_TTL_MS) {
                changed = true
                retained += item.copy(
                    attempts = item.attempts + 1,
                    nextAttemptAt = now + retryDelay(item.attempts + 1)
                )
                logEvent("outbox", "retry ${item.packet.type.name} attempt ${item.attempts + 1}")
            } else {
                changed = true
                logEvent("outbox", "expired ${item.packet.type.name} after ${item.attempts} attempts")
            }
        }
        if (changed) {
            outboxItems.value = retained
            chatStore.saveOutbox(retained)
        }
    }

    private suspend fun sendTargetedOrBroadcast(peerId: String, packet: MeshPacket): Boolean {
        if (isPeerBlocked(peerId)) return false
        for (transport in transports) {
            if (transport.send(peerId, packet)) return true
        }
        return broadcastPacket(packet)
    }

    private fun dropOutboxForBlockedPeer(peerId: String) {
        val retained = outboxItems.value.filterNot { it.targetPeerId == peerId }
        if (retained.size == outboxItems.value.size) return
        outboxItems.value = retained
        chatStore.saveOutbox(retained)
        logEvent("block", "removed pending sends to ${peerId.take(6)}")
    }

    private fun retryDelay(attempts: Int): Long {
        val seconds = when {
            attempts <= 1 -> 2
            attempts <= 3 -> 10
            attempts <= 6 -> 30
            else -> 120
        }
        return seconds * 1_000L
    }

    @Synchronized
    private fun logEvent(category: String, detail: String) {
        val sanitized = detail
            .replace(Regex("\\s+"), " ")
            .take(MAX_DIAGNOSTIC_DETAIL)
        val event = DiagnosticEvent(
            createdAt = System.currentTimeMillis(),
            category = category,
            detail = sanitized
        )
        diagnosticEvents.value = (diagnosticEvents.value + event).takeLast(MAX_DIAGNOSTIC_EVENTS)
    }

    private fun channelLabel(channel: String): String =
        if (channel.startsWith(DIRECT_CHANNEL_PREFIX)) "direct" else "#$channel"

    private fun signedPacket(
        id: String = UUID.randomUUID().toString(),
        type: PacketType,
        channel: String,
        payload: String,
        ttl: Int
    ): MeshPacket {
        val unsigned = MeshPacket(
            id = id,
            type = type,
            originId = localIdentity.peerId,
            originName = localIdentity.displayName,
            originPublicKey = localIdentity.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = ttl,
            channel = channel,
            payload = payload
        )
        return unsigned.copy(signature = localIdentity.sign(MeshPacketCodec.signingBytes(unsigned)))
    }

    private fun encryptedRoomPacket(
        id: String = UUID.randomUUID().toString(),
        channel: String,
        roomKey: RoomKey,
        envelope: RoomEnvelope
    ): MeshPacket {
        val encryptedPayload = RoomCrypto.encrypt(
            roomKey = roomKey,
            packetId = id,
            plaintext = MeshPacketCodec.encodePayload(envelope).toByteArray(Charsets.UTF_8)
        )
        return signedPacket(
            id = id,
            type = PacketType.RoomEncrypted,
            channel = channel,
            payload = MeshPacketCodec.encodePayload(encryptedPayload),
            ttl = DEFAULT_TTL
        )
    }

    private fun encryptedDirectPacket(
        peerId: String,
        peerPublicKey: String,
        id: String,
        envelope: DirectEnvelope
    ): MeshPacket {
        val encrypted = cryptoBox.encryptFor(
            recipientPublicKey = IdentityStore.decodePublicKey(peerPublicKey),
            plaintext = MeshPacketCodec.encodePayload(envelope).toByteArray(Charsets.UTF_8),
            aad = directAad(id, peerId)
        )
        val payload = DirectPayload(
            recipientId = peerId,
            ephemeralPublicKey = encrypted.ephemeralPublicKey,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext
        )
        return signedPacket(
            id = id,
            type = PacketType.Direct,
            channel = "dm:$peerId",
            payload = MeshPacketCodec.encodePayload(payload),
            ttl = DEFAULT_TTL
        )
    }

    private fun directAad(packetId: String, recipientId: String): ByteArray =
        "airchat-direct-v1:$packetId:$recipientId".toByteArray(Charsets.UTF_8)

    private fun formatBytes(bytes: Int): String {
        return when {
            bytes < 1_024 -> "$bytes B"
            bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
            else -> "${bytes / (1_024 * 1_024)} MB"
        }
    }

    private data class PendingFileAssembly(
        val senderId: String,
        val senderName: String,
        val channel: String,
        var manifest: FileManifest? = null,
        val chunks: MutableMap<Int, FileChunk> = mutableMapOf(),
        var verified: Boolean = true
    )

    private companion object {
        const val DEFAULT_TTL = 7
        const val MAX_MESSAGES = 500
        const val MAX_OUTBOX_ITEMS = 1_024
        const val MAX_RECEIVED_FILES = 50
        const val MAX_COURIER_PACKETS = 256
        const val MAX_LOCKED_ROOM_PACKETS = 128
        const val MAX_DIAGNOSTIC_EVENTS = 80
        const val MAX_DIAGNOSTIC_DETAIL = 120
        const val OUTBOX_TTL_MS = 24L * 60L * 60L * 1_000L
        const val DIRECT_CHANNEL_PREFIX = "dm:"
    }
}
