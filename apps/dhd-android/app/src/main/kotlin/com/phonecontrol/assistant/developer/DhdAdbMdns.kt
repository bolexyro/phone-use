package com.phonecontrol.assistant.developer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

internal data class DhdAdbEndpoint(
    val host: String,
    val port: Int,
)

/**
 * Discovers only an ADB service advertised by this same phone.
 *
 * mDNS is also visible to nearby devices, so a resolved address is accepted
 * only when it belongs to one of this phone's local interfaces. The returned
 * endpoint is then pinned to loopback; DHD must never run commands on a
 * different device found on the Wi-Fi network.
 */
internal class DhdAdbMdns(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
        ?: error("Network service discovery is unavailable.")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: DiscoveryListener? = null
    private var running = false

    fun start(
        serviceType: String,
        onResolved: (DhdAdbEndpoint) -> Unit,
        onError: (Throwable) -> Unit,
        onLost: () -> Unit = {},
    ) {
        stop()
        val discovery = DiscoveryListener(manager, serviceType, onResolved, onError, onLost)
        listener = discovery
        running = true
        mainHandler.post {
            if (!running || listener !== discovery) return@post
            runCatching {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
            }.onFailure(onError)
        }
    }

    fun stop() {
        val discovery = listener
        listener = null
        running = false
        if (discovery != null) {
            discovery.invalidate()
            mainHandler.post {
                runCatching { manager.stopServiceDiscovery(discovery) }
            }
        }
    }

    private class DiscoveryListener(
        private val manager: NsdManager,
        private val serviceType: String,
        private val onResolved: (DhdAdbEndpoint) -> Unit,
        private val onError: (Throwable) -> Unit,
        private val onLost: () -> Unit,
    ) : NsdManager.DiscoveryListener {
        @Volatile
        private var active = true
        private var resolved = false
        private var resolvedServiceName: String? = null

        fun invalidate() {
            active = false
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            if (active) onError(IOException("ADB service discovery failed to start ($errorCode)."))
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            // Stopping an already-finished discovery is harmless. Do not turn
            // this cleanup callback into a false connection error.
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!active || resolved || !isSameServiceType(serviceInfo.serviceType)) return
            runCatching {
                manager.resolveService(serviceInfo, ResolveListener(this))
            }.onFailure { error -> if (active) onError(error) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (active &&
                isSameServiceType(serviceInfo.serviceType) &&
                resolvedServiceName == serviceInfo.serviceName
            ) {
                onLost()
            }
        }

        fun onResolved(serviceInfo: NsdServiceInfo) {
            if (!active || resolved) return
            val host = serviceInfo.host ?: return
            if (!isLocalAddress(host)) return
            if (serviceInfo.port !in 1..65535 || !isPortOccupiedLocally(serviceInfo.port)) return
            resolved = true
            resolvedServiceName = serviceInfo.serviceName
            onResolved(DhdAdbEndpoint(host = LOOPBACK_HOST, port = serviceInfo.port))
        }

        private fun isSameServiceType(value: String?): Boolean =
            value?.trimEnd('.') == serviceType.trimEnd('.')

    }

    private class ResolveListener(
        private val discovery: DiscoveryListener,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            discovery.onResolved(serviceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val LOOPBACK_HOST = "127.0.0.1"

        private fun isLocalAddress(address: java.net.InetAddress): Boolean {
            if (address.isLoopbackAddress) return true
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence()?.any { networkInterface ->
                    networkInterface.inetAddresses.asSequence().any { it == address }
                } == true
            }.getOrDefault(false)
        }

        /** A local ADB listener makes the advertised port unavailable to bind. */
        private fun isPortOccupiedLocally(port: Int): Boolean = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(LOOPBACK_HOST, port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }
    }
}
