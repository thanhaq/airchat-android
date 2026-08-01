package dev.offlinemesh.airchat.transport

import android.net.nsd.NsdManager
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFailureCatalogTest {
    @Test
    fun labelsNsdAlreadyActiveFailures() {
        assertEquals(
            "LAN discovery failed: FAILURE_ALREADY_ACTIVE (3); retry after stopping duplicate discovery",
            TransportFailureCatalog.nsdFailure("discovery", NsdManager.FAILURE_ALREADY_ACTIVE)
        )
    }

    @Test
    fun labelsNsdMaxLimitFailures() {
        val label = TransportFailureCatalog.nsdFailure("registration", NsdManager.FAILURE_MAX_LIMIT)

        assertTrue(label.contains("FAILURE_MAX_LIMIT (4)"))
        assertTrue(label.contains("too many NSD operations active"))
    }

    @Test
    fun labelsWifiDirectBusyFailures() {
        assertEquals(
            "Wi-Fi Direct discovery failed: BUSY (2); Wi-Fi Direct stack is busy; retry after refresh",
            TransportFailureCatalog.wifiP2pFailure("discovery", WifiP2pManager.BUSY)
        )
    }

    @Test
    fun labelsUnknownWifiDirectFailures() {
        val label = TransportFailureCatalog.wifiP2pFailure("connect", 99)

        assertTrue(label.contains("UNKNOWN_WIFI_P2P_REASON (99)"))
        assertTrue(label.contains("capture Logcat"))
    }
}
