@file:Suppress("DEPRECATION")

package dev.offlinemesh.airchat.transport.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerConnectionState
import dev.offlinemesh.airchat.model.TransportKind
import dev.offlinemesh.airchat.model.TransportState
import dev.offlinemesh.airchat.model.TransportStatus
import dev.offlinemesh.airchat.protocol.MeshPacket
import dev.offlinemesh.airchat.protocol.MeshPacketCodec
import dev.offlinemesh.airchat.transport.MeshTransport
import dev.offlinemesh.airchat.transport.TransportEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
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

class LanTransport(
    context: Context,
    private val identity: IdentityStore,
    private val scope: CoroutineScope
) : MeshTransport {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val peerEndpoints = ConcurrentHashMap<String, Endpoint>()
    private val eventFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    private val peerState = MutableStateFlow<List<Peer>>(emptyList())
    private val statusState = MutableStateFlow(
        TransportStatus(NAME, TransportState.Stopped, "LAN discovery stopped")
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override val name: String = NAME
    override val status: StateFlow<TransportStatus> = statusState.asStateFlow()
    override val peers: StateFlow<List<Peer>> = peerState.asStateFlow()
    override val events: Flow<TransportEvent> = eventFlow.asSharedFlow()

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext
        statusState.value = TransportStatus(NAME, TransportState.Starting, "Opening local socket")
        serverSocket = ServerSocket(0).also { socket ->
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
            registerService(socket.localPort)
            discoverServices()
        }
        statusState.value = TransportStatus(NAME, TransportState.Ready, "Advertising on local Wi-Fi")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { registrationListener?.let(nsdManager::unregisterService) }
        runCatching { discoveryListener?.let(nsdManager::stopServiceDiscovery) }
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        registrationListener = null
        discoveryListener = null
        serverSocket = null
        peerEndpoints.clear()
        peerState.value = emptyList()
        statusState.value = TransportStatus(NAME, TransportState.Stopped, "LAN discovery stopped")
    }

    override suspend fun broadcast(packet: MeshPacket): Boolean {
        var delivered = false
        peerEndpoints.values.forEach { endpoint ->
            if (sendToEndpoint(endpoint, packet)) delivered = true
        }
        return delivered
    }

    override suspend fun send(peerId: String, packet: MeshPacket): Boolean {
        val endpoint = peerEndpoints[peerId] ?: return false
        return sendToEndpoint(endpoint, packet)
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "${identity.displayName}-${identity.peerId.take(6)}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("peerId", identity.peerId)
            setAttribute("name", identity.displayName)
            setAttribute("publicKey", identity.publicKeyEncoded)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                statusState.value = TransportStatus(NAME, TransportState.Ready, "LAN service registered")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                statusState.value = TransportStatus(NAME, TransportState.Degraded, "LAN registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                statusState.value = TransportStatus(NAME, TransportState.Ready, "Scanning local Wi-Fi")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (identity.peerId in serviceInfo.serviceName) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val peerId = serviceInfo.attributes["peerId"]?.toString(Charsets.UTF_8)
                if (peerId != null) {
                    peerEndpoints.remove(peerId)
                    publishPeers()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                statusState.value = TransportStatus(NAME, TransportState.Failed, "LAN discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                statusState.value = TransportStatus(NAME, TransportState.Degraded, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val peerId = resolved.attributes["peerId"]?.toString(Charsets.UTF_8)
                    ?: IdentityStore.stablePeerId(resolved.serviceName)
                if (peerId == identity.peerId) return
                val endpoint = Endpoint(
                    peerId = peerId,
                    name = resolved.attributes["name"]?.toString(Charsets.UTF_8) ?: resolved.serviceName,
                    publicKey = resolved.attributes["publicKey"]?.toString(Charsets.UTF_8),
                    address = resolved.host,
                    port = resolved.port
                )
                peerEndpoints[peerId] = endpoint
                publishPeers()
                scope.launch { send(peerId, helloPacket()) }
            }
        })
    }

    private suspend fun acceptLoop(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: continue
            scope.launch(Dispatchers.IO) { readSocket(socket) }
        }
    }

    private suspend fun sendToEndpoint(endpoint: Endpoint, packet: MeshPacket): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), CONNECT_TIMEOUT_MS)
                OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { writer ->
                    writer.write(MeshPacketCodec.encode(packet))
                    writer.write("\n")
                    writer.flush()
                }
            }
        }.isSuccess
    }

    private suspend fun readSocket(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            reader.lineSequence().forEach { line ->
                val packet = MeshPacketCodec.decode(line) ?: return@forEach
                val remote = Peer(
                    id = packet.originId,
                    name = packet.originName,
                    transport = TransportKind.Lan,
                    publicKey = packet.originPublicKey,
                    address = it.inetAddress.hostAddress,
                    connectionState = PeerConnectionState.Connected
                )
                peerEndpoints.putIfAbsent(
                    packet.originId,
                    Endpoint(
                        peerId = packet.originId,
                        name = packet.originName,
                        publicKey = packet.originPublicKey,
                        address = it.inetAddress,
                        port = DEFAULT_PORT_HINT
                    )
                )
                publishPeers()
                eventFlow.tryEmit(TransportEvent.PacketReceived(NAME, packet, remote))
            }
        }
    }

    private fun publishPeers() {
        peerState.value = peerEndpoints.values.map { endpoint ->
            Peer(
                id = endpoint.peerId,
                name = endpoint.name,
                transport = TransportKind.Lan,
                publicKey = endpoint.publicKey,
                address = "${endpoint.address.hostAddress}:${endpoint.port}",
                connectionState = PeerConnectionState.Discovered
            )
        }
    }

    private fun helloPacket(): MeshPacket {
        val unsigned = MeshPacket(
            id = "hello-${identity.peerId}-${System.currentTimeMillis()}",
            type = dev.offlinemesh.airchat.protocol.PacketType.Hello,
            originId = identity.peerId,
            originName = identity.displayName,
            originPublicKey = identity.publicKeyEncoded,
            createdAt = System.currentTimeMillis(),
            ttl = 1,
            channel = "control",
            payload = "hello"
        )
        return unsigned.copy(signature = identity.sign(MeshPacketCodec.signingBytes(unsigned)))
    }

    private data class Endpoint(
        val peerId: String,
        val name: String,
        val publicKey: String?,
        val address: InetAddress,
        val port: Int
    )

    private companion object {
        const val NAME = "LAN"
        const val SERVICE_TYPE = "_airchat._tcp."
        const val CONNECT_TIMEOUT_MS = 1_500
        const val DEFAULT_PORT_HINT = 45454
    }
}
