package dev.offlinemesh.airchat.transport

import android.net.nsd.NsdManager
import android.net.wifi.p2p.WifiP2pManager

object TransportFailureCatalog {
    fun nsdFailure(operation: String, errorCode: Int): String {
        val label = when (errorCode) {
            NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
            NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
            NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
            else -> "UNKNOWN_NSD_FAILURE"
        }
        val hint = when (errorCode) {
            NsdManager.FAILURE_ALREADY_ACTIVE -> "retry after stopping duplicate discovery"
            NsdManager.FAILURE_MAX_LIMIT -> "too many NSD operations active"
            NsdManager.FAILURE_INTERNAL_ERROR -> "toggle Wi-Fi or retry discovery"
            else -> "capture Logcat and retry discovery"
        }
        return "LAN $operation failed: $label ($errorCode); $hint"
    }

    fun wifiP2pFailure(action: String, reason: Int): String {
        val label = when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> "UNKNOWN_WIFI_P2P_REASON"
        }
        val hint = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "device or firmware does not support Wi-Fi Direct"
            WifiP2pManager.BUSY -> "Wi-Fi Direct stack is busy; retry after refresh"
            NO_SERVICE_REQUESTS -> "service discovery has no active requests"
            WifiP2pManager.ERROR -> "toggle Wi-Fi Direct or retry discovery"
            else -> "capture Logcat and retry discovery"
        }
        return "Wi-Fi Direct $action failed: $label ($reason); $hint"
    }

    private const val NO_SERVICE_REQUESTS = 3
}
