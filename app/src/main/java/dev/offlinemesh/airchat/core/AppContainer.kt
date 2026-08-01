package dev.offlinemesh.airchat.core

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
    private val backgroundPowerState = MutableStateFlow(
        BackgroundMeshPowerPolicy.evaluate(readDevicePowerSnapshot())
    )
    private val backgroundNotifications = BackgroundMeshNotifications(
        context = appContext,
        router = router,
        backgroundMeshEnabled = backgroundMeshState,
        isUiVisible = { isUiVisible() },
        scope = scope
    )
    val backgroundMeshEnabled: StateFlow<Boolean> = backgroundMeshState.asStateFlow()
    val backgroundPowerStatus: StateFlow<BackgroundPowerStatus> = backgroundPowerState.asStateFlow()

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
            refreshPowerPolicyLocked()
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
            refreshPowerPolicyLocked()
            router.start()
        }
    }

    fun disableBackgroundMesh() {
        synchronized(lifecycleLock) {
            backgroundMeshState.value = false
            refreshPowerPolicyLocked()
            stopRouterIfIdleLocked()
        }
    }

    fun refreshPowerPolicy(): BackgroundPowerStatus =
        synchronized(lifecycleLock) { refreshPowerPolicyLocked() }

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
            refreshPowerPolicyLocked()
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

    private fun refreshPowerPolicyLocked(): BackgroundPowerStatus {
        val status = BackgroundMeshPowerPolicy.evaluate(readDevicePowerSnapshot())
        backgroundPowerState.value = status
        router.updatePowerPolicy(status.policy)
        return status
    }

    private fun isUiVisible(): Boolean =
        synchronized(lifecycleLock) { uiSessions > 0 }

    private fun readDevicePowerSnapshot(): DevicePowerSnapshot {
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return DevicePowerSnapshot(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            isBatterySaver = powerManager?.isPowerSaveMode == true
        )
    }
}
