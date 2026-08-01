@file:Suppress("DEPRECATION")

package dev.offlinemesh.airchat.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.offlinemesh.airchat.core.AirChatLog
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerConnectionState
import dev.offlinemesh.airchat.model.TransportKind
import dev.offlinemesh.airchat.model.TransportState
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.protocol.MeshPacketCodec
import dev.offlinemesh.airchat.protocol.PacketType
import dev.offlinemesh.airchat.transport.MeshTransport
import dev.offlinemesh.airchat.transport.TransportEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class WifiDirectTransport(
    context: Context,
    private val identity: IdentityStore,
    private val scope: CoroutineScope
) : MeshTransport {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, Looper.getMainLooper(), null)
    private val discoveredDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    private val connectedSockets = ConcurrentHashMap<String, Socket>()
    private val eventFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    private val peerState = MutableStateFlow<List<Peer>>(emptyList())
    private val statusState = MutableStateFlow(
        TransportStatus(NAME, TransportState.Stopped, "Wi-Fi Direct stopped")
    )

    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    override val name: String = NAME
    override val status: StateFlow<TransportStatus> = statusState.asStateFlow()
    override val peers: StateFlow<List<Peer>> = peerState.asStateFlow()
    override val events: Flow<TransportEvent> = eventFlow.asSharedFlow()

    override suspend fun start() {
        if (receiver != null) return
        statusState.value = TransportStatus(NAME, TransportState.Starting, "Starting Wi-Fi Direct")
        receiver = createReceiver().also {
            ContextCompat.registerReceiver(
                appContext,
                it,
                intentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        startGroupServer()
        manager.discoverPeers(channel, actionListener("Wi-Fi Direct discovery started"))
    }

    override suspend fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        runCatching { manager.stopPeerDiscovery(channel, null) }
        connectedSockets.values.forEach { runCatching { it.close() } }
        connectedSockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        peerState.value = emptyList()
        statusState.value = TransportStatus(NAME, TransportState.Stopped, "Wi-Fi Direct stopped")
    }

    override suspend fun broadcast(packet: MeshPacket): Boolean {
        var delivered = false
        connectedSockets.values.forEach { socket ->
            if (writeSocket(socket, packet)) delivered = true
        }
        return delivered
    }

    override suspend fun send(peerId: String, packet: MeshPacket): Boolean {
        val socket = connectedSockets[peerId] ?: return false
        return writeSocket(socket, packet)
    }

    fun connect(peerId: String) {
        val device = discoveredDevices[peerId] ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        peerState.value = peerState.value.map {
            if (it.id == peerId) it.copy(connectionState = PeerConnectionState.Connecting) else it
        }
        manager.connect(channel, config, actionListener("Connecting to ${device.deviceName}"))
    }

    private fun createReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        statusState.value = if (enabled) {
                            TransportStatus(NAME, TransportState.Ready, "Wi-Fi Direct available")
                        } else {
                            TransportStatus(NAME, TransportState.Failed, "Wi-Fi Direct disabled")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager.requestPeers(channel) { devices -> publishDevices(devices) }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            manager.requestConnectionInfo(channel) { info -> handleConnectionInfo(info) }
                        }
                    }
                }
            }
        }
    }

    private fun publishDevices(devices: WifiP2pDeviceList) {
        devices.deviceList.forEach { device ->
            val id = IdentityStore.stablePeerId(device.deviceAddress)
            discoveredDevices[id] = device
        }
        peerState.value = devices.deviceList.map { device ->
            val id = IdentityStore.stablePeerId(device.deviceAddress)
            Peer(
                id = id,
                name = device.deviceName.ifBlank { "Wi-Fi peer" },
                transport = TransportKind.WifiDirect,
                address = device.deviceAddress,
                connectionState = when (device.status) {
                    WifiP2pDevice.CONNECTED -> PeerConnectionState.Connected
                    WifiP2pDevice.INVITED -> PeerConnectionState.Connecting
                    WifiP2pDevice.UNAVAILABLE -> PeerConnectionState.Unreachable
                    else -> PeerConnectionState.Discovered
                }
            )
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        statusState.value = TransportStatus(
            NAME,
            TransportState.Ready,
            if (info.isGroupOwner) "Hosting Wi-Fi Direct group" else "Joined Wi-Fi Direct group"
        )
        if (!info.isGroupOwner && info.groupOwnerAddress != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    Socket().also { socket ->
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, PORT), CONNECT_TIMEOUT_MS)
                        sendHello(socket)
                        connectedSockets["group-owner"] = socket
                        readSocket(socket)
                    }
                }.onFailure { error ->
                    statusState.value = TransportStatus(
                        NAME,
                        TransportState.Degraded,
                        "Group owner socket unavailable: ${AirChatLog.throwableLabel(error)}"
                    )
                }
            }
        }
    }

    private fun startGroupServer() {
        if (serverSocket != null) return
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                serverSocket = ServerSocket(PORT)
                while (serverSocket?.isClosed == false) {
                    val socket = serverSocket?.accept() ?: continue
                    sendHello(socket)
                    scope.launch(Dispatchers.IO) { readSocket(socket) }
                }
            }.onFailure { error ->
                statusState.value = TransportStatus(
                    NAME,
                    TransportState.Degraded,
                    "Port $PORT unavailable: ${AirChatLog.throwableLabel(error)}"
                )
            }
        }
    }

    private suspend fun readSocket(socket: Socket) = withContext(Dispatchers.IO) {
        runCatching {
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                reader.lineSequence().forEach { line ->
                    val packet = MeshPacketCodec.decode(line) ?: return@forEach
                    connectedSockets[packet.originId] = socket
                    val remote = Peer(
                        id = packet.originId,
                        name = packet.originName,
                        transport = TransportKind.WifiDirect,
                        publicKey = packet.originPublicKey,
                        address = socket.inetAddress.hostAddress,
                        connectionState = PeerConnectionState.Connected
                    )
                    upsertPeer(remote)
                    eventFlow.tryEmit(TransportEvent.PacketReceived(NAME, packet, remote))
                }
            }
        }.onFailure { error ->
            statusState.value = TransportStatus(
                NAME,
                TransportState.Degraded,
                "Wi-Fi Direct read failed: ${AirChatLog.throwableLabel(error)}"
            )
        }
    }

    private suspend fun writeSocket(socket: Socket, packet: MeshPacket): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).apply {
                write(MeshPacketCodec.encode(packet))
                write("\n")
                flush()
            }
        }.onFailure { error ->
            statusState.value = TransportStatus(
                NAME,
                TransportState.Degraded,
                "Wi-Fi Direct send failed: ${AirChatLog.throwableLabel(error)}"
            )
        }.isSuccess
    }

    private suspend fun sendHello(socket: Socket) {
        val unsigned = MeshPacket(
            id = "hello-${identity.peerId}-${System.currentTimeMillis()}",
            type = PacketType.Hello,
            originId = identity.peerId,
            originName = identity.displayName,
            originPublicKey = identity.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = 1,
            channel = "control",
            payload = "hello"
        )
        writeSocket(socket, unsigned.copy(signature = identity.sign(MeshPacketCodec.signingBytes(unsigned))))
    }

    private fun upsertPeer(peer: Peer) {
        peerState.value = (peerState.value.filterNot { it.id == peer.id } + peer)
    }

    private fun actionListener(successDetail: String): WifiP2pManager.ActionListener {
        return object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                statusState.value = TransportStatus(NAME, TransportState.Ready, successDetail)
            }

            override fun onFailure(reason: Int) {
                statusState.value = TransportStatus(NAME, TransportState.Degraded, "Wi-Fi Direct action failed: $reason")
            }
        }
    }

    private fun intentFilter() = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private companion object {
        const val NAME = "Wi-Fi Direct"
        const val PORT = 45455
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
