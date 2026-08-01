package dev.offlinemesh.airchat.transport

import dev.offlinemesh.airchat.crypto.CryptoBox
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.crypto.MeshIdentity
import dev.offlinemesh.airchat.crypto.RoomCrypto
import dev.offlinemesh.airchat.model.CourierPolicy
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.MeshPowerPolicy
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerConnectionState
import dev.offlinemesh.airchat.model.PeerTrustState
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.model.TransportKind
import dev.offlinemesh.airchat.model.TrustedPeer
import dev.offlinemesh.airchat.protocol.AckPayload
import dev.offlinemesh.airchat.protocol.AckStatus
import dev.offlinemesh.airchat.protocol.CourierReceiptPayload
import dev.offlinemesh.airchat.protocol.DirectPayload
import dev.offlinemesh.airchat.protocol.DirectEnvelope
import dev.offlinemesh.airchat.protocol.DirectKind
import dev.offlinemesh.airchat.protocol.FileTransferCodec
import dev.offlinemesh.airchat.protocol.HistoryRequestPayload
import dev.offlinemesh.airchat.protocol.HistoryResponsePayload
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.protocol.MeshPacketCodec
import dev.offlinemesh.airchat.protocol.PacketType
import dev.offlinemesh.airchat.protocol.RoomEncryptedPayload
import dev.offlinemesh.airchat.protocol.RoomEnvelope
import dev.offlinemesh.airchat.protocol.RoomEnvelopeKind
import dev.offlinemesh.airchat.store.InMemoryChatStore
import dev.offlinemesh.airchat.store.InMemoryCourierStore
import dev.offlinemesh.airchat.store.InMemoryPeerBlockStore
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
        assertTrue(router.diagnostics.value.any { it.category == "outbox" })
        assertTrue(router.diagnostics.value.none { it.detail.contains("hello offline") })
    }

    @Test
    fun sendPrivateRoomMessageBroadcastsCiphertextOnly() = runTest {
        val identity = TestIdentity("alice")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = identity,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.setRoomPassphrase(channel = "field_ops", passphrase = "shared field key")

        router.sendChannelMessage(channel = "field_ops", body = "move at dawn")

        val status = router.privateRoomStatuses.value.getValue("field_ops")
        val packet = transport.broadcastedPackets.single()
        val payload = MeshPacketCodec.decodePayload<RoomEncryptedPayload>(packet.payload)
        val decrypted = RoomCrypto.decrypt(
            channel = "field_ops",
            passphrase = "shared field key",
            packetId = packet.id,
            payload = payload ?: error("missing room payload")
        )
        val envelope = MeshPacketCodec.decodePayload<RoomEnvelope>(String(decrypted ?: ByteArray(0)))
        assertEquals(PacketType.RoomEncrypted, packet.type)
        assertEquals(status, router.privateRoomStatus("field_ops"))
        assertEquals("fair", status.strengthLabel)
        assertTrue(status.verificationCode.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")))
        assertTrue(!packet.payload.contains("move at dawn"))
        assertEquals(RoomEnvelopeKind.Text, envelope?.kind)
        assertEquals("move at dawn", envelope?.body)
        assertEquals("move at dawn", router.messages.value.single().body)
    }

    @Test
    fun receivesPrivateRoomMessageWithMatchingPassphrase() = runTest {
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
        router.setRoomPassphrase(channel = "field_ops", passphrase = "shared field key")
        advanceUntilIdle()
        val packet = signedRoomPacket(
            identity = alice,
            id = "room-secret-1",
            channel = "field_ops",
            passphrase = "shared field key",
            envelope = RoomEnvelope(RoomEnvelopeKind.Text, "rally at point two")
        )

        transport.emitPacket(packet, peerFor(alice))
        advanceUntilIdle()

        assertEquals("rally at point two", router.messages.value.single().body)
        assertEquals(DeliveryState.Verified, router.messages.value.single().state)
        assertTrue(transport.broadcastedPackets.any { it.type == PacketType.Ack })
    }

    @Test
    fun buffersPrivateRoomMessageUntilPassphraseIsEntered() = runTest {
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
        val packet = signedRoomPacket(
            identity = alice,
            id = "room-secret-buffered",
            channel = "field_ops",
            passphrase = "shared field key",
            envelope = RoomEnvelope(RoomEnvelopeKind.Text, "cached until unlock")
        )

        transport.emitPacket(packet, peerFor(alice))
        advanceUntilIdle()
        assertEquals(DeliveryState.Locked, router.messages.value.single().state)
        assertTrue(!router.messages.value.single().body.contains("cached until unlock"))

        router.setRoomPassphrase(channel = "field_ops", passphrase = "shared field key")
        advanceUntilIdle()

        assertEquals(1, router.messages.value.size)
        assertEquals("cached until unlock", router.messages.value.single().body)
        assertEquals(DeliveryState.Verified, router.messages.value.single().state)
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
    fun requestsPublicHistoryWhenPeerAppears() = runTest {
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

        val request = transport.sentPackets.single { (peerId, packet) ->
            peerId == bob.peerId && packet.type == PacketType.HistoryRequest
        }.second
        val payload = MeshPacketCodec.decodePayload<HistoryRequestPayload>(request.payload)
        assertEquals("_airchat_history", request.channel)
        assertEquals(1, request.ttl)
        assertTrue(payload?.knownPacketIds?.isEmpty() == true)
        assertTrue(payload?.channels?.isEmpty() == true)
        assertEquals(24, payload?.maxPackets)
        assertTrue(router.diagnostics.value.any { it.category == "history" && it.detail.contains("requested") })
    }

    @Test
    fun respondsToHistoryRequestWithUnknownPublicPackets() = runTest {
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
        transport.sentPackets.clear()

        router.sendChannelMessage(channel = "lobby", body = "missed public hello")
        advanceUntilIdle()
        val originalPacket = transport.broadcastedPackets.single { it.type == PacketType.Chat }
        val request = signedPacket(
            identity = bob,
            id = "history-request-1",
            type = PacketType.HistoryRequest,
            channel = "_airchat_history",
            payload = MeshPacketCodec.encodePayload(
                HistoryRequestPayload(
                    knownPacketIds = emptyList(),
                    maxPackets = 10
                )
            )
        )

        transport.emitPacket(request, peerFor(bob))
        advanceUntilIdle()

        val response = transport.sentPackets.single { (peerId, packet) ->
            peerId == bob.peerId && packet.type == PacketType.HistoryResponse
        }.second
        val payload = MeshPacketCodec.decodePayload<HistoryResponsePayload>(response.payload)
        assertEquals("history-request-1", payload?.requestId)
        assertEquals(listOf(originalPacket.id), payload?.packets?.map { it.id })
        assertTrue(response.payload.contains("missed public hello"))
        assertTrue(router.diagnostics.value.any { it.category == "history" && it.detail.contains("sent 1 public") })
    }

    @Test
    fun historyResponseDoesNotIncludePrivateRoomMessages() = runTest {
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
        transport.sentPackets.clear()
        router.setRoomPassphrase(channel = "field_ops", passphrase = "shared field key")
        router.sendChannelMessage(channel = "field_ops", body = "private room secret")
        advanceUntilIdle()
        val request = signedPacket(
            identity = bob,
            id = "history-request-private",
            type = PacketType.HistoryRequest,
            channel = "_airchat_history",
            payload = MeshPacketCodec.encodePayload(
                HistoryRequestPayload(
                    knownPacketIds = emptyList(),
                    maxPackets = 10
                )
            )
        )

        transport.emitPacket(request, peerFor(bob))
        advanceUntilIdle()

        assertTrue(transport.sentPackets.none { it.second.type == PacketType.HistoryResponse })
        assertTrue(router.diagnostics.value.any { it.category == "history" && it.detail.contains("no public") })
    }

    @Test
    fun importsVerifiedHistoryResponseWithoutAckOrRelay() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val carol = TestIdentity("carol")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val missedPacket = signedPacket(
            identity = carol,
            id = "missed-chat-1",
            type = PacketType.Chat,
            channel = "lobby",
            payload = "restored from public history"
        )
        val response = signedPacket(
            identity = alice,
            id = "history-response-1",
            type = PacketType.HistoryResponse,
            channel = "_airchat_history",
            payload = MeshPacketCodec.encodePayload(
                HistoryResponsePayload(
                    requestId = "history-request-1",
                    packets = listOf(missedPacket)
                )
            )
        )

        transport.emitPacket(response, peerFor(alice))
        advanceUntilIdle()

        assertEquals(1, router.messages.value.size)
        assertEquals("restored from public history", router.messages.value.single().body)
        assertEquals(DeliveryState.Verified, router.messages.value.single().state)
        assertTrue(transport.broadcastedPackets.none { it.type == PacketType.Ack || it.id == missedPacket.id })
        assertTrue(router.diagnostics.value.any { it.category == "history" && it.detail.contains("imported 1") })
    }

    @Test
    fun queuesVerifiedRelayPacketForCourierWhenBroadcastFails() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val charlie = TestIdentity("charlie")
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val signed = signedPacket(
            identity = alice,
            id = "courier-1",
            type = PacketType.Chat,
            channel = "lobby",
            payload = "carry this"
        )

        transport.emitPacket(signed, peerFor(alice))
        advanceUntilIdle()

        assertEquals(1, router.courierQueueSize.value)

        transport.broadcastedPackets.clear()
        transport.broadcastSucceeds = true
        transport.publishPeers(listOf(peerFor(charlie)))
        advanceUntilIdle()

        assertEquals(0, router.courierQueueSize.value)
        val relayed = transport.broadcastedPackets.single { it.type == PacketType.Chat }
        assertEquals("courier-1", relayed.id)
        assertEquals(6, relayed.ttl)
        assertTrue(bob.peerId in relayed.path)
    }

    @Test
    fun courierQueueEnforcesPerOriginQuota() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val store = InMemoryCourierStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        router.updateCourierPolicy(
            CourierPolicy(
                enabled = true,
                retentionMinutes = 15,
                maxPacketsPerOrigin = 2
            )
        )
        advanceUntilIdle()

        listOf("quota-1", "quota-2", "quota-3").forEach { id ->
            transport.emitPacket(
                signedPacket(
                    identity = alice,
                    id = id,
                    type = PacketType.Chat,
                    channel = "lobby",
                    payload = "carry $id"
                ),
                peerFor(alice)
            )
        }
        advanceUntilIdle()

        assertEquals(2, router.courierQueueSize.value)
        assertEquals(listOf("quota-2", "quota-3"), store.loadCourierPackets().map { it.packet.id })
        assertTrue(router.diagnostics.value.any { it.category == "courier" && it.detail.contains("quota evicted") })
    }

    @Test
    fun queuesCourierPacketAndSendsSignedReceipt() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "receipt-courier",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "please carry"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()

        val receipt = transport.sentPackets.map { it.second }.single { it.type == PacketType.CourierReceipt }
        val payload = MeshPacketCodec.decodePayload<CourierReceiptPayload>(receipt.payload)
        assertEquals("receipt-courier", payload?.packetId)
        assertEquals("lobby", receipt.channel)
        assertEquals(1, receipt.ttl)
        assertEquals(bob.peerId, receipt.originId)
        assertTrue(router.diagnostics.value.any { it.category == "courier" && it.detail.contains("sent receipt") })
    }

    @Test
    fun originLogsVerifiedCourierReceiptForLocalMessage() = runTest {
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
        router.sendChannelMessage(channel = "lobby", body = "relay me")
        advanceUntilIdle()
        val localMessageId = router.messages.value.single().id
        val receipt = signedPacket(
            identity = bob,
            id = "courier-receipt:$localMessageId:${bob.peerId}",
            type = PacketType.CourierReceipt,
            channel = "lobby",
            payload = MeshPacketCodec.encodePayload(
                CourierReceiptPayload(
                    packetId = localMessageId,
                    storedAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 15L * 60L * 1_000L,
                    remainingTtl = 6
                )
            )
        )

        transport.emitPacket(receipt, peerFor(bob))
        advanceUntilIdle()

        assertTrue(router.diagnostics.value.any { it.category == "courier" && it.detail.contains("receipt for") })
    }

    @Test
    fun conservePowerModeClampsRelayedTtl() = runTest {
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
        router.updatePowerPolicy(MeshPowerPolicy.Conserve)
        advanceUntilIdle()

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "conserve-relay",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "relay with lower ttl"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()

        val relayed = transport.broadcastedPackets.single { it.id == "conserve-relay" }
        assertEquals(2, relayed.ttl)
        assertTrue(router.diagnostics.value.any { it.category == "power" && it.detail.contains("mode conserve") })
    }

    @Test
    fun criticalPowerModeDoesNotStoreNewCourierPackets() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val store = InMemoryCourierStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        router.updatePowerPolicy(MeshPowerPolicy.Critical)
        advanceUntilIdle()

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "critical-courier",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "do not store"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()

        assertEquals(0, router.courierQueueSize.value)
        assertTrue(store.loadCourierPackets().isEmpty())
        assertTrue(router.diagnostics.value.any { it.category == "power" && it.detail.contains("courier storage paused") })
    }

    @Test
    fun loadsPersistedCourierPacketsAndFlushesOnPeerContact() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val charlie = TestIdentity("charlie")
        val courierStore = InMemoryCourierStore()
        val firstTransport = FakeTransport().apply { broadcastSucceeds = false }
        val firstRouter = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = courierStore,
            transports = listOf(firstTransport),
            scope = routerScope()
        )
        firstRouter.start()
        advanceUntilIdle()

        firstTransport.emitPacket(
            signedPacket(
                identity = alice,
                id = "persisted-courier",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "carry across restart"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()

        assertEquals(1, courierStore.loadCourierPackets().size)

        val secondTransport = FakeTransport().apply { broadcastSucceeds = false }
        val secondRouter = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = courierStore,
            transports = listOf(secondTransport),
            scope = routerScope()
        )
        secondRouter.start()
        advanceUntilIdle()

        assertEquals(1, secondRouter.courierQueueSize.value)

        secondTransport.broadcastedPackets.clear()
        secondTransport.broadcastSucceeds = true
        secondTransport.publishPeers(listOf(peerFor(charlie)))
        advanceUntilIdle()

        assertEquals(0, secondRouter.courierQueueSize.value)
        assertTrue(courierStore.loadCourierPackets().isEmpty())
        assertEquals("persisted-courier", secondTransport.broadcastedPackets.single { it.type == PacketType.Chat }.id)
    }

    @Test
    fun doesNotCourierUnverifiedRelayPackets() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        advanceUntilIdle()
        val unsigned = MeshPacket(
            id = "unverified-courier",
            type = PacketType.Chat,
            originId = alice.peerId,
            originName = alice.displayName,
            originPublicKey = alice.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = 7,
            channel = "lobby",
            payload = "do not relay"
        )

        transport.emitPacket(unsigned.copy(signature = "invalid"), peerFor(alice))
        advanceUntilIdle()

        assertEquals(1, router.messages.value.size)
        assertEquals(DeliveryState.Unverified, router.messages.value.single().state)
        assertEquals(0, router.courierQueueSize.value)
        assertTrue(transport.broadcastedPackets.isEmpty())
    }

    @Test
    fun courierPolicyCanDisableRelayStorage() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val store = InMemoryCourierStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        router.updateCourierPolicy(CourierPolicy(enabled = false, retentionMinutes = 15))
        advanceUntilIdle()

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "disabled-courier",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "relay disabled"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()

        assertEquals(0, router.courierQueueSize.value)
        assertTrue(store.loadCourierPackets().isEmpty())
        assertTrue(router.diagnostics.value.any { it.category == "courier" && it.detail.contains("relay disabled") })
    }

    @Test
    fun courierPolicyControlsRetentionWindow() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val store = InMemoryCourierStore()
        val transport = FakeTransport().apply { broadcastSucceeds = false }
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            courierStore = store,
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        router.updateCourierPolicy(CourierPolicy(enabled = true, retentionMinutes = 5))
        val before = System.currentTimeMillis()
        advanceUntilIdle()

        transport.emitPacket(
            signedPacket(
                identity = alice,
                id = "retention-courier",
                type = PacketType.Chat,
                channel = "lobby",
                payload = "short retention"
            ),
            peerFor(alice)
        )
        advanceUntilIdle()
        val after = System.currentTimeMillis()

        val packet = store.loadCourierPackets().single()
        assertTrue(packet.expiresAt >= before + 5L * 60L * 1_000L)
        assertTrue(packet.expiresAt <= after + 5L * 60L * 1_000L)
    }

    @Test
    fun blockedPeerPacketsAreDroppedBeforeDisplayAckAndRelay() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val blockStore = InMemoryPeerBlockStore()
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            peerBlockStore = blockStore,
            courierStore = InMemoryCourierStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        transport.publishPeers(listOf(peerFor(alice)))
        advanceUntilIdle()

        assertTrue(router.blockPeer(alice.peerId))
        advanceUntilIdle()
        val packet = signedPacket(
            identity = alice,
            id = "blocked-chat",
            type = PacketType.Chat,
            channel = "lobby",
            payload = "blocked text"
        )

        transport.emitPacket(packet, peerFor(alice))
        advanceUntilIdle()

        assertEquals(setOf(alice.peerId), blockStore.loadBlockedPeers())
        assertTrue(router.peers.value.single { it.id == alice.peerId }.isBlocked)
        assertTrue(router.messages.value.isEmpty())
        assertEquals(0, router.courierQueueSize.value)
        assertTrue(transport.broadcastedPackets.none { it.type == PacketType.Ack || it.id == "blocked-chat" })
        assertTrue(router.diagnostics.value.any { it.category == "block" && it.detail.contains("dropped Chat") })
    }

    @Test
    fun blockedPeerPreventsDirectSendsAndCanBeUnblocked() = runTest {
        val alice = TestIdentity("alice")
        val bob = TestIdentity("bob")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = bob,
            chatStore = InMemoryChatStore(),
            peerBlockStore = InMemoryPeerBlockStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.start()
        transport.publishPeers(listOf(peerFor(alice)))
        advanceUntilIdle()

        router.blockPeer(alice.peerId)
        advanceUntilIdle()
        val blockedSend = router.sendDirectMessage(alice.peerId, "nope")

        router.unblockPeer(alice.peerId)
        advanceUntilIdle()
        val allowedSend = router.sendDirectMessage(alice.peerId, "hello again")

        assertEquals(false, blockedSend)
        assertEquals(true, allowedSend)
        assertEquals(1, router.messages.value.size)
        assertTrue(!router.peers.value.single { it.id == alice.peerId }.isBlocked)
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
        assertTrue(router.diagnostics.value.any { it.category == "guard" && it.detail.contains("invalid ttl") })
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
        transport.sentPackets.clear()

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
    fun sendPrivateRoomFileUsesEncryptedRoomPacketsOnly() = runTest {
        val alice = TestIdentity("alice")
        val transport = FakeTransport()
        val router = MeshRouter(
            localIdentity = alice,
            chatStore = InMemoryChatStore(),
            transports = listOf(transport),
            scope = routerScope()
        )
        router.setRoomPassphrase(channel = "field_ops", passphrase = "shared field key")

        val delivered = router.sendChannelFile(
            channel = "field_ops",
            fileName = "route-plan.txt",
            mimeType = "text/plain",
            bytes = "private file payload".toByteArray()
        )

        assertTrue(delivered)
        assertTrue(transport.broadcastedPackets.isNotEmpty())
        assertTrue(transport.broadcastedPackets.all { it.type == PacketType.RoomEncrypted })
        assertTrue(transport.broadcastedPackets.none { it.payload.contains("route-plan") })
        assertEquals("Sent private-room", router.messages.value.single().body.take(17))
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
        transport.sentPackets.clear()

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
        assertEquals(0, router.courierQueueSize.value)
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

    private fun signedRoomPacket(
        identity: TestIdentity,
        id: String,
        channel: String,
        passphrase: String,
        envelope: RoomEnvelope
    ): MeshPacket {
        val encrypted = RoomCrypto.encrypt(
            channel = channel,
            passphrase = passphrase,
            packetId = id,
            plaintext = MeshPacketCodec.encodePayload(envelope).toByteArray()
        )
        return signedPacket(
            identity = identity,
            id = id,
            type = PacketType.RoomEncrypted,
            channel = channel,
            payload = MeshPacketCodec.encodePayload(encrypted)
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
