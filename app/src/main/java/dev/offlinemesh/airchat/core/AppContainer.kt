package dev.offlinemesh.airchat.core

import android.Manifest
import android.content.Context
import android.os.Build
import dev.offlinemesh.airchat.crypto.IdentityStore
import dev.offlinemesh.airchat.store.PreferencesCourierStore
import dev.offlinemesh.airchat.store.PreferencesPeerBlockStore
import dev.offlinemesh.airchat.store.PreferencesPeerTrustStore
import dev.offlinemesh.airchat.store.PreferencesChatStore
import dev.offlinemesh.airchat.store.PreferencesReceivedFileStore
import dev.offlinemesh.airchat.store.PreferencesRoomPreferencesStore
import dev.offlinemesh.airchat.service.BackgroundMeshNotifications
import dev.offlinemesh.airchat.transport.MeshRouter
import dev.offlinemesh.airchat.transport.lan.LanTransport
import dev.offlinemesh.airchat.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val backgroundMeshState = MutableStateFlow(false)
    private var uiSessions = 0

    val identityStore = IdentityStore(appContext)
    val chatStore = PreferencesChatStore(appContext)
    val peerTrustStore = PreferencesPeerTrustStore(appContext)
    val receivedFileStore = PreferencesReceivedFileStore(appContext)
    val courierStore = PreferencesCourierStore(appContext)
    val roomPreferencesStore = PreferencesRoomPreferencesStore(appContext)
    val peerBlockStore = PreferencesPeerBlockStore(appContext)
    val lanTransport = LanTransport(appContext, identityStore, scope)
    val wifiDirectTransport = WifiDirectTransport(appContext, identityStore, scope)
    val router = MeshRouter(
        localIdentity = identityStore,
        chatStore = chatStore,
        peerTrustStore = peerTrustStore,
        peerBlockStore = peerBlockStore,
        receivedFileStore = receivedFileStore,
        courierStore = courierStore,
        transports = listOf(lanTransport, wifiDirectTransport),
        scope = scope
    )
    private val backgroundNotifications = BackgroundMeshNotifications(
        context = appContext,
        router = router,
        backgroundMeshEnabled = backgroundMeshState,
        isUiVisible = { isUiVisible() },
        scope = scope
    )
    val backgroundMeshEnabled: StateFlow<Boolean> = backgroundMeshState.asStateFlow()

    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.toTypedArray()
    }

    fun startUiSession() {
        synchronized(lifecycleLock) {
            uiSessions += 1
            router.start()
        }
        backgroundNotifications.clearVisibleNotifications()
    }

    fun stopUiSession() {
        synchronized(lifecycleLock) {
            uiSessions = (uiSessions - 1).coerceAtLeast(0)
            stopRouterIfIdleLocked()
        }
    }

    fun enableBackgroundMesh() {
        synchronized(lifecycleLock) {
            backgroundMeshState.value = true
            router.start()
        }
    }

    fun disableBackgroundMesh() {
        synchronized(lifecycleLock) {
            backgroundMeshState.value = false
            stopRouterIfIdleLocked()
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            uiSessions = 0
            backgroundMeshState.value = false
            router.stop()
        }
        backgroundNotifications.stop()
        scope.cancel()
    }

    fun panicWipe() {
        synchronized(lifecycleLock) {
            router.stop()
            router.clearLocalState()
            roomPreferencesStore.clear()
            identityStore.wipeFromDisk()
            if (uiSessions > 0 || backgroundMeshState.value) {
                router.start()
            }
        }
    }

    private fun stopRouterIfIdleLocked() {
        if (uiSessions == 0 && !backgroundMeshState.value) {
            router.stop()
        }
    }

    private fun isUiVisible(): Boolean =
        synchronized(lifecycleLock) { uiSessions > 0 }
}
