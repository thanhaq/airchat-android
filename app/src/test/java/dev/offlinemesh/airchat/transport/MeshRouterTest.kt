package dev.offlinemesh.airchat.transport

import dev.offlinemesh.airchat.crypto.CryptoBox
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerConnectionState
import dev.offlinemesh.airchat.model.PeerTrustState
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.TransportKind
import dev.offlinemesh.airchat.model.TrustedPeer
import dev.offlinemesh.airchat.protocol.AckPayload
import dev.offlinemesh.airchat.protocol.AckStatus
import dev.offlinemesh.airchat.protocol.DirectPayload
import dev.offlinemesh.airchat.protocol.DirectEnvelope
import dev.offlinemesh.airchat.protocol.DirectKind
import dev.offlinemesh.airchat.protocol.FileTransferCodec
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.protocol.MeshPacketCodec
import dev.offlinemesh.airchat.protocol.PacketType
import dev.offlinemesh.airchat.store.InMemoryChatStore
import dev.offlinemesh.airchat.store.InMemoryPeerTrustStore
import dev.offlinemesh.airchat.store.InMemoryReceivedFileStore
import dev.offlinemesh.airchat.testutil.FakeTransport
import dev.offlinemesh.airchat.testutil.TestIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshRouterTest {
    @Test
    fun sendChannelMessageQueuesOutboxWhenNoTransportDelivers() = runTest {
        val identity = TestIdentity("alice")
        val store = InMemoryChatStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = identity,
            chatStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )

        router.sendChannelMessage(channel = "lobby", body = "hello offline")

        assertEquals(1, router.messages.value.size)
        assertEquals(DeliveryState.Pending, router.messages.value.single().state)
        assertEquals(1, store.loadOutbox().size)
    }

    @Test
    fun flushesOutboxWhenTransportStartsDelivering() = runTest {
        val identity = TestIdentity("alice")
        val store = InMemoryChatStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = identity,
            chatStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()

        router.sendChannelMessage(channel = "lobby", body = "queued")
        transport.broadcastSucceeds = true
        transport.publishPeers(listOf(peerFor(TestIdentity("bob"))))
        advanceUntilIdle()

        assertTrue(store.loadOutbox().isEmpty())
        assertEquals(DeliveryState.Sent, router.messages.value.single().state)
    }

    @Test
    fun receivesVerifiedChatAndRelaysWithMutableTransportFields() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val now = System.currentTimeMillis()
        val unsigned = MeshPacket(
            id = "packet-1",
            type = PacketType.Chat,
            originId = alice.peerId,
            originName = alice.displayName,
            originPublicKey = alice.publicKeyEncoded,
            createdAt = now,
            ttl = 6,
            channel = "lobby",
            payload = "signed hello",
            path = listOf("relay-a")
        )
        val signed = unsigned.copy(signature = alice.sign(MeshPacketCodec.signingBytes(unsigned)))

        transport.emitPacket(signed, peerFor(alice))
        advanceUntilIdle()

        assertEquals(1, router.messages.value.size)
        assertEquals(DeliveryState.Verified, router.messages.value.single().state)
        val ack = transport.broadcastedPackets.single { it.type == PacketType.Ack }
        val ackPayload = MeshPacketCodec.decodePayload<AckPayload>(ack.payload)
        assertEquals("packet-1", ackPayload?.packetId)
        val relayed = transport.broadcastedPackets.single { it.type == PacketType.Chat }
        assertEquals(5, relayed.ttl)
        assertTrue(bob.peerId in relayed.path)
    }

    @Test
    fun receivesAckAndMarksLocalMessageReceived() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()

        router.sendChannelMessage(channel = "lobby", body = "needs receipt")
        val sentMessage = router.messages.value.single()
        val ackPacket = signedPacket(
            identity = bob,
            id = "ack:${sentMessage.id}:${bob.peerId}",
            type = PacketType.Ack,
            channel = "lobby",
            payload = MeshPacketCodec.encodePayload(
                AckPayload(
                    packetId = sentMessage.id,
                    receivedAt = System.currentTimeMillis(),
                    status = AckStatus.Verified
                )
            )
        )

        transport.emitPacket(ackPacket, peerFor(bob))
        advanceUntilIdle()

        assertEquals(DeliveryState.Received, router.messages.value.single().state)
    }

    @Test
    fun ignoresDirectMessageAckFromOtherPeer() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val charlie = TestIdentity("charlie")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        transport.publishPeers(listOf(peerFor(bob), peerFor(charlie)))
        advanceUntilIdle()

        val sent = router.sendDirectMessage(bob.peerId, "private receipt")
        val messageId = router.messages.value.single().id
        val unauthorizedAck = signedPacket(
            identity = charlie,
            id = "ack:$messageId:${charlie.peerId}",
            type = PacketType.Ack,
            channel = "dm:${bob.peerId}",
            payload = MeshPacketCodec.encodePayload(
                AckPayload(
                    packetId = messageId,
                    receivedAt = System.currentTimeMillis(),
                    status = AckStatus.Verified
                )
            )
        )
        val authorizedAck = signedPacket(
            identity = bob,
            id = "ack:$messageId:${bob.peerId}",
            type = PacketType.Ack,
            channel = "dm:${bob.peerId}",
            payload = MeshPacketCodec.encodePayload(
                AckPayload(
                    packetId = messageId,
                    receivedAt = System.currentTimeMillis(),
                    status = AckStatus.Verified
                )
            )
        )

        transport.emitPacket(unauthorizedAck, peerFor(charlie))
        advanceUntilIdle()
        assertTrue(sent)
        assertEquals(DeliveryState.Sent, router.messages.value.single().state)

        transport.emitPacket(authorizedAck, peerFor(bob))
        advanceUntilIdle()
        assertEquals(DeliveryState.Received, router.messages.value.single().state)
    }

    @Test
    fun rejectsPacketsWithInvalidTtlBeforeDisplayOrRelay() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val unsigned = MeshPacket(
            id = "bad-ttl",
            type = PacketType.Chat,
            originId = alice.peerId,
            originName = alice.displayName,
            originPublicKey = alice.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = 99,
            channel = "lobby",
            payload = "drop me"
        )

        transport.emitPacket(unsigned.copy(signature = alice.sign(MeshPacketCodec.signingBytes(unsigned))), peerFor(alice))
        advanceUntilIdle()

        assertTrue(router.messages.value.isEmpty())
        assertTrue(transport.broadcastedPackets.isEmpty())
    }

    @Test
    fun receivesEncryptedDirectMessage() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val now = System.currentTimeMillis()
        val packetId = "dm-1"
        val encrypted = CryptoBox().encryptFor(
            recipientPublicKey = IdentityStore.decodePublicKey(bob.publicKeyEncoded),
            plaintext = "secret hello".toByteArray(),
            aad = "airchat-direct-v1:$packetId:${bob.peerId}".toByteArray()
        )
        val payload = DirectPayload(
            recipientId = bob.peerId,
            ephemeralPublicKey = encrypted.ephemeralPublicKey,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext
        )
        val unsigned = MeshPacket(
            id = packetId,
            type = PacketType.Direct,
            originId = alice.peerId,
            originName = alice.displayName,
            originPublicKey = alice.publicKeyEncoded,
            createdAt = now,
            ttl = 7,
            channel = "dm:${bob.peerId}",
            payload = MeshPacketCodec.encodePayload(payload)
        )
        val signed = unsigned.copy(signature = alice.sign(MeshPacketCodec.signingBytes(unsigned)))

        transport.emitPacket(signed, peerFor(alice))
        advanceUntilIdle()

        assertEquals("secret hello", router.messages.value.single().body)
        assertEquals(DeliveryState.Verified, router.messages.value.single().state)
    }

    @Test
    fun trustPeerMarksMatchingKeyAsTrusted() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val trustStore = InMemoryPeerTrustStore()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            peerTrustStore = trustStore,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        transport.publishPeers(listOf(peerFor(bob)))
        advanceUntilIdle()

        val trusted = router.trustPeer(bob.peerId)

        assertTrue(trusted)
        assertEquals(PeerTrustState.Trusted, router.peers.value.single().trustState)
        assertEquals(bob.publicKeyEncoded, trustStore.loadTrustedPeers().getValue(bob.peerId).publicKey)
    }

    @Test
    fun keyChangedTrustedPeerBlocksDirectMessages() = runTest {
        val alice = TestIdentity("alice")
        val oldBob = TestIdentity("bob-old")
        val newBob = TestIdentity("bob-new")
        val fixedPeerId = "stable-peer-id"
        val transport = FakeTransport()
        val trustStore = InMemoryPeerTrustStore().apply {
            trustPeer(
                TrustedPeer(
                    peerId = fixedPeerId,
                    displayName = "bob",
                    publicKey = oldBob.publicKeyEncoded,
                    trustedAt = 1L
                )
            )
        }
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            peerTrustStore = trustStore,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        transport.publishPeers(
            listOf(
                peerFor(newBob).copy(
                    id = fixedPeerId,
                    name = "bob"
                )
            )
        )
        advanceUntilIdle()

        val sent = router.sendDirectMessage(fixedPeerId, "do not send")

        assertEquals(PeerTrustState.KeyChanged, router.peers.value.single().trustState)
        assertTrue(!sent)
        assertTrue(transport.sentPackets.isEmpty())
    }

    @Test
    fun sendChannelFileBroadcastsManifestAndChunks() = runTest {
        val alice = TestIdentity("alice")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        val bytes = ByteArray(40_000) { (it % 251).toByte() }

        val delivered = router.sendChannelFile(
            channel = "lobby",
            fileName = "map.bin",
            mimeType = "application/octet-stream",
            bytes = bytes
        )

        assertTrue(delivered)
        assertEquals(listOf(PacketType.FileManifest, PacketType.FileChunk, PacketType.FileChunk), transport.broadcastedPackets.map { it.type })
        assertEquals("Sent", router.messages.value.single().body.take(4))
    }

    @Test
    fun loadsReceivedFilesFromStoreOnStartup() = runTest {
        val identity = TestIdentity("alice")
        val storedFile = ReceivedFile(
            id = "stored-file",
            fileName = "stored.txt",
            mimeType = "text/plain",
            totalBytes = 11,
            sha256 = "hash",
            senderId = "sender",
            senderName = "sender",
            channel = "lobby",
            receivedAt = 7L,
            bytes = "hello store".toByteArray()
        )
        val receivedFileStore = InMemoryReceivedFileStore().apply {
            saveReceivedFiles(listOf(storedFile))
        }

        val router = MeshRouter(
            localIdentity = identity,
            chatStore = InMemoryChatStore(),
            receivedFileStore = receivedFileStore,
            transports = listOf(FakeTransport()),
            scope = routerScope()
        )

        assertEquals("stored.txt", router.receivedFiles.value.single().fileName)
        assertEquals("hello store", String(router.receivedFiles.value.single().bytes))
    }

    @Test
    fun sendDirectFileUsesEncryptedDirectPacketsOnly() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        transport.publishPeers(listOf(peerFor(bob)))
        advanceUntilIdle()

        val delivered = router.sendDirectFile(
            peerId = bob.peerId,
            fileName = "secret-map.txt",
            mimeType = "text/plain",
            bytes = "encrypted route".toByteArray()
        )

        assertTrue(delivered)
        assertTrue(transport.sentPackets.isNotEmpty())
        assertTrue(transport.sentPackets.all { it.second.type == PacketType.Direct })
        assertTrue(transport.sentPackets.none { it.second.payload.contains("secret-map") })
        assertEquals("Sent encrypted", router.messages.value.single().body.take(14))
    }

    @Test
    fun receivesAndReassemblesPublicFileTransfer() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val receivedFileStore = InMemoryReceivedFileStore()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            receivedFileStore = receivedFileStore,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val plan = FileTransferCodec.createPlan(
            fileName = "notes.txt",
            mimeType = "text/plain",
            bytes = "offline file bytes".toByteArray(),
            chunkSize = 5,
            transferId = "transfer-1"
        )
        val manifestPacket = signedPacket(
            identity = alice,
            id = "file:${plan.manifest.transferId}:manifest",
            type = PacketType.FileManifest,
            channel = "lobby",
            payload = MeshPacketCodec.encodePayload(plan.manifest)
        )
        val chunkPackets = plan.chunks.map { chunk ->
            signedPacket(
                identity = alice,
                id = "file:${chunk.transferId}:chunk:${chunk.index}",
                type = PacketType.FileChunk,
                channel = "lobby",
                payload = MeshPacketCodec.encodePayload(chunk)
            )
        }

        transport.emitPacket(manifestPacket, peerFor(alice))
        chunkPackets.forEach { transport.emitPacket(it, peerFor(alice)) }
        advanceUntilIdle()

        assertEquals(1, router.receivedFiles.value.size)
        assertEquals("notes.txt", router.receivedFiles.value.single().fileName)
        assertEquals("notes.txt", receivedFileStore.loadReceivedFiles().single().fileName)
        assertEquals("offline file bytes", String(router.receivedFiles.value.single().bytes))
        assertEquals("Received", router.messages.value.single().body.take(8))
    }

    @Test
    fun receivesAndReassemblesEncryptedDirectFileTransfer() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val plan = FileTransferCodec.createPlan(
            fileName = "secret.txt",
            mimeType = "text/plain",
            bytes = "encrypted file bytes".toByteArray(),
            chunkSize = 6,
            transferId = "direct-transfer-1"
        )
        val manifestPacket = encryptedDirectPacket(
            sender = alice,
            recipient = bob,
            id = "dfile:${plan.manifest.transferId}:manifest",
            envelope = DirectEnvelope(
                kind = DirectKind.FileManifest,
                body = MeshPacketCodec.encodePayload(plan.manifest)
            )
        )
        val chunkPackets = plan.chunks.map { chunk ->
            encryptedDirectPacket(
                sender = alice,
                recipient = bob,
                id = "dfile:${chunk.transferId}:chunk:${chunk.index}",
                envelope = DirectEnvelope(
                    kind = DirectKind.FileChunk,
                    body = MeshPacketCodec.encodePayload(chunk)
                )
            )
        }

        transport.emitPacket(manifestPacket, peerFor(alice))
        chunkPackets.forEach { transport.emitPacket(it, peerFor(alice)) }
        advanceUntilIdle()

        assertEquals(1, router.receivedFiles.value.size)
        assertEquals("secret.txt", router.receivedFiles.value.single().fileName)
        assertEquals("dm:${alice.peerId}", router.receivedFiles.value.single().channel)
        assertEquals("encrypted file bytes", String(router.receivedFiles.value.single().bytes))
    }

    @Test
    fun ignoresPlainFileTransfersInDirectChannels() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val plan = FileTransferCodec.createPlan(
            fileName = "secret.txt",
            mimeType = "text/plain",
            bytes = "do not accept plaintext dm files".toByteArray(),
            chunkSize = 8,
            transferId = "transfer-direct"
        )

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "file:${plan.manifest.transferId}:manifest",
                type = PacketType.FileManifest,
                channel = "dm:${bob.peerId}",
                payload = MeshPacketCodec.encodePayload(plan.manifest)
            ),
            peerFor(alice)
        )
        plan.chunks.forEach { chunk ->
            transport.emitPacket(
                signedPacket(
                    identity = alice,
                    id = "file:${chunk.transferId}:chunk:${chunk.index}",
                    type = PacketType.FileChunk,
                    channel = "dm:${bob.peerId}",
                    payload = MeshPacketCodec.encodePayload(chunk)
                ),
                peerFor(alice)
            )
        }
        advanceUntilIdle()

        assertTrue(router.receivedFiles.value.isEmpty())
        assertTrue(router.messages.value.isEmpty())
    }

    @Test
    fun clearLocalStateClearsMessagesOutboxAndPeers() = runTest {
        val identity = TestIdentity("alice")
        val store = InMemoryChatStore()
        val receivedFileStore = InMemoryReceivedFileStore().apply {
            saveReceivedFiles(
                listOf(
                    ReceivedFile(
                        id = "stored-file",
                        fileName = "stored.txt",
                        mimeType = "text/plain",
                        totalBytes = 6,
                        sha256 = "hash",
                        senderId = "sender",
                        senderName = "sender",
                        channel = "lobby",
                        receivedAt = 7L,
                        bytes = "stored".toByteArray()
                    )
                )
            )
        }
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = identity,
            chatStore = store,
            receivedFileStore = receivedFileStore,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()

        transport.publishPeers(listOf(peerFor(TestIdentity("bob"))))
        router.sendChannelMessage(channel = "lobby", body = "clear me")
        advanceUntilIdle()
        router.clearLocalState()

        assertTrue(router.messages.value.isEmpty())
        assertTrue(router.peers.value.isEmpty())
        assertTrue(store.loadOutbox().isEmpty())
        assertTrue(store.loadMessages().isEmpty())
        assertTrue(receivedFileStore.loadReceivedFiles().isEmpty())
    }

    @Test
    fun localIdentityFieldsReflectIdentityRotation() = runTest {
        val first = TestIdentity("first")
        val second = TestIdentity("second")
        val identity = MutableTestIdentity(first)
        val router = MeshRouter(
            localIdentity = identity,
            chatStore = InMemoryChatStore(),
            transports = listOf(FakeTransport()),
            scope = routerScope()
        )
        val originalPeerId = router.localPeerId

        identity.delegate = second

        assertNotEquals(originalPeerId, router.localPeerId)
        assertEquals(second.publicKeyEncoded, router.localPublicKey)
        assertEquals(second.displayName, router.localName)
    }

    private fun peerFor(identity: TestIdentity) = Peer(
        id = identity.peerId,
        name = identity.displayName,
        transport = TransportKind.Lan,
        publicKey = identity.publicKeyEncoded,
        connectionState = PeerConnectionState.Connected
    )

    private fun TestScope.routerScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun signedPacket(
        identity: TestIdentity,
        id: String,
        type: PacketType,
        channel: String,
        payload: String
    ): MeshPacket {
        val unsigned = MeshPacket(
            id = id,
            type = type,
            originId = identity.peerId,
            originName = identity.displayName,
            originPublicKey = identity.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = 7,
            channel = channel,
            payload = payload
        )
        return unsigned.copy(signature = identity.sign(MeshPacketCodec.signingBytes(unsigned)))
    }

    private fun encryptedDirectPacket(
        sender: TestIdentity,
        recipient: TestIdentity,
        id: String,
        envelope: DirectEnvelope
    ): MeshPacket {
        val encrypted = CryptoBox().encryptFor(
            recipientPublicKey = IdentityStore.decodePublicKey(recipient.publicKeyEncoded),
            plaintext = MeshPacketCodec.encodePayload(envelope).toByteArray(),
            aad = "airchat-direct-v1:$id:${recipient.peerId}".toByteArray()
        )
        val payload = DirectPayload(
            recipientId = recipient.peerId,
            ephemeralPublicKey = encrypted.ephemeralPublicKey,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext
        )
        return signedPacket(
            identity = sender,
            id = id,
            type = PacketType.Direct,
            channel = "dm:${recipient.peerId}",
            payload = MeshPacketCodec.encodePayload(payload)
        )
    }

    private class MutableTestIdentity(
        var delegate: TestIdentity
    ) : MeshIdentity {
        override val peerId: String
            get() = delegate.peerId
        override val displayName: String
            get() = delegate.displayName
        override val publicKeyEncoded: String
            get() = delegate.publicKeyEncoded

        override fun sign(bytes: ByteArray): String = delegate.sign(bytes)

        override fun privateKey() = delegate.privateKey()
    }
}
