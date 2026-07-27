package dev.offlinemesh.airchat.transport

import dev.offlinemesh.airchat.crypto.CryptoBox
import dev.offlinemesh.airchat.crypto.EncryptedPayload
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import dev.offlinemesh.airchat.crypto.RoomCrypto
import dev.offlinemesh.airchat.crypto.RoomKey
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.CourierPacket
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.OutboxItem
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerTrustState
import dev.offlinemesh.airchat.model.ReceivedFile
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
import dev.offlinemesh.airchat.store.InMemoryPeerTrustStore
import dev.offlinemesh.airchat.store.InMemoryReceivedFileStore
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
    private val statuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    private val cryptoBox = CryptoBox()
    private val packetGuard = PacketGuard()
    private val fileAssemblies = mutableMapOf<String, PendingFileAssembly>()
    private val courierPackets = loadInitialCourierPackets()
    private val courierCount = MutableStateFlow(courierPackets.size)
    private val roomKeys = mutableMapOf<String, RoomKey>()
    private val encryptedRoomChannels = MutableStateFlow<Set<String>>(emptySet())
    private val lockedRoomPackets = linkedMapOf<String, MeshPacket>()
    private val routerJobs = mutableListOf<Job>()
    private var started = false

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
    val courierQueueSize: StateFlow<Int> = courierCount.asStateFlow()
    val privateRoomChannels: StateFlow<Set<String>> = encryptedRoomChannels.asStateFlow()

    fun start() {
        if (started) return
        started = true
        transports.forEach { transport ->
            routerJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.peers.collect { peers ->
                    updatePeerIndex(peers)
                    flushOutbox()
                    flushCourierQueue()
                }
            }
            routerJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.status.collect { status ->
                    val merged = statuses.value.filterNot { it.name == status.name } + status
                    statuses.value = merged.sortedBy { it.name }
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
    }

    fun refreshTransports() {
        transports.forEach { transport ->
            scope.launch {
                transport.stop()
                transport.start()
            }
        }
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
        encryptedRoomChannels.value = emptySet()
        lockedRoomPackets.clear()
        courierStore.clear()
        chatStore.clear()
        receivedFileStore.clear()
        trustedPeers.value = emptyMap()
        peerTrustStore.clear()
    }

    fun trustPeer(peerId: String): Boolean {
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
        encryptedRoomChannels.value = roomKeys.keys.toSet()
        scope.launch {
            unlockBufferedRoomPackets(normalized)
        }
        return true
    }

    fun clearRoomPassphrase(channel: String): Boolean {
        val removed = roomKeys.remove(channel.trim())
        removed?.bytes?.fill(0)
        encryptedRoomChannels.value = roomKeys.keys.toSet()
        return removed != null
    }

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
        return delivered
    }

    fun connectWifiDirectPeer(peerId: String) {
        val wifiDirect = transports.filterIsInstance<WifiDirectTransport>().firstOrNull() ?: return
        wifiDirect.connect(peerId)
    }

    private suspend fun handlePacket(event: TransportEvent.PacketReceived) {
        val packet = event.packet
        when (packetGuard.inspect(packet)) {
            PacketGuardDecision.Accepted -> Unit
            PacketGuardDecision.RateLimited,
            is PacketGuardDecision.Rejected -> return
        }
        if (!deduper.remember(packet.id)) return
        if (packet.originId == localPeerId) return

        event.remotePeer?.let { peer ->
            updatePeerIndex(listOf(peer))
        }

        val verified = packet.signature?.let {
            IdentityStore.verify(
                publicKeyEncoded = packet.originPublicKey,
                bytes = MeshPacketCodec.signingBytes(packet),
                signatureEncoded = it
            )
        } ?: false

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
        visiblePeers.value = peerIndex.value.values
            .filterNot { it.id == localPeerId }
            .map { peer -> peer.copy(trustState = trustStateFor(peer)) }
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
        val relayed = packet.copy(
            ttl = packet.ttl - 1,
            path = packet.path + localPeerId
        )
        if (!broadcastPacket(relayed)) {
            queueCourierPacket(relayed)
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
        if (packet.ttl <= 0 || packet.originId == localPeerId) return
        val now = System.currentTimeMillis()
        courierPackets.remove(packet.id)
        courierPackets[packet.id] = CourierPacket(
            packet = packet,
            expiresAt = now + COURIER_TTL_MS
        )
        while (courierPackets.size > MAX_COURIER_PACKETS) {
            val eldest = courierPackets.keys.firstOrNull() ?: break
            courierPackets.remove(eldest)
        }
        persistCourierQueue()
    }

    private suspend fun flushCourierQueue() {
        pruneCourierQueue()
        if (courierPackets.isEmpty()) return
        val retained = linkedMapOf<String, CourierPacket>()
        courierPackets.values.forEach { item ->
            if (!broadcastPacket(item.packet)) {
                retained[item.packet.id] = item
            }
        }
        courierPackets.clear()
        courierPackets.putAll(retained)
        persistCourierQueue()
    }

    private fun pruneCourierQueue() {
        if (courierPackets.isEmpty()) return
        val now = System.currentTimeMillis()
        val expired = courierPackets.filterValues { it.expiresAt <= now }.keys
        if (expired.isEmpty()) return
        expired.forEach(courierPackets::remove)
        persistCourierQueue()
    }

    private fun persistCourierQueue() {
        val packets = courierPackets.values.toList()
        courierCount.value = packets.size
        courierStore.saveCourierPackets(packets)
    }

    private fun loadInitialCourierPackets(): LinkedHashMap<String, CourierPacket> {
        val now = System.currentTimeMillis()
        val stored = courierStore.loadCourierPackets()
        val packets = stored
            .filter { item ->
                item.expiresAt > now &&
                    item.packet.ttl > 0 &&
                    item.packet.originId != localPeerId &&
                    localPeerId in item.packet.path
            }
            .takeLast(MAX_COURIER_PACKETS)
        if (packets.size != stored.size) {
            courierStore.saveCourierPackets(packets)
        }
        return linkedMapOf<String, CourierPacket>().apply {
            packets.forEach { item -> put(item.packet.id, item) }
        }
    }

    private fun queueOutbox(packet: MeshPacket, targetPeerId: String?) {
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
    }

    private suspend fun flushOutbox() {
        val now = System.currentTimeMillis()
        val retained = mutableListOf<OutboxItem>()
        var changed = false
        outboxItems.value.forEach { item ->
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
            } else if (now - item.createdAt < OUTBOX_TTL_MS) {
                changed = true
                retained += item.copy(
                    attempts = item.attempts + 1,
                    nextAttemptAt = now + retryDelay(item.attempts + 1)
                )
            } else {
                changed = true
            }
        }
        if (changed) {
            outboxItems.value = retained
            chatStore.saveOutbox(retained)
        }
    }

    private suspend fun sendTargetedOrBroadcast(peerId: String, packet: MeshPacket): Boolean {
        for (transport in transports) {
            if (transport.send(peerId, packet)) return true
        }
        return broadcastPacket(packet)
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
        const val OUTBOX_TTL_MS = 24L * 60L * 60L * 1_000L
        const val COURIER_TTL_MS = 15L * 60L * 1_000L
        const val DIRECT_CHANNEL_PREFIX = "dm:"
    }
}
