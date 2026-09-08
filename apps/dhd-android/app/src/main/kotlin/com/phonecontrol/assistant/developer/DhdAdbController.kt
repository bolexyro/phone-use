package com.phonecontrol.assistant.developer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DeveloperConnectionState {
    CHECKING,
    UNSUPPORTED,
    WIRELESS_DEBUGGING_OFF,
    PAIRING_REQUIRED,
    PAIRING_SEARCHING,
    PAIRING_SERVICE_FOUND,
    CONNECTING,
    READY,
    ERROR,
}

data class DeveloperModeStatus(
    val state: DeveloperConnectionState = DeveloperConnectionState.CHECKING,
    val paired: Boolean = false,
    val message: String = "Checking Wireless Debugging…",
) {
    val privilegedApiReady: Boolean
        get() = state == DeveloperConnectionState.READY
}

/**
 * Owns DHD's one-time Wireless Debugging bootstrap and its long-lived local
 * shell-UID maintenance connection. It never enables Wireless Debugging
 * itself; the user explicitly turns that maintenance switch on when Android
 * has restarted the maintenance process or pairing is needed.
 */
class DhdAdbController(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mdns = DhdAdbMdns(appContext)
    private val commandMutex = Mutex()
    private val maintenanceBootstrap = DhdMaintenanceBootstrap(appContext, preferences)
    private val key by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DhdAdbKey.from(appContext) }
    private val _status = MutableStateFlow(DeveloperModeStatus())
    private val started = AtomicBoolean(false)
    private var discoveryTimeoutJob: Job? = null
    private var pairingJob: Job? = null
    private var maintenanceProbeJob: Job? = null
    private var maintenanceMonitorJob: Job? = null
    private var maintenanceRecoveryJob: Job? = null
    private var endpoint: DhdAdbEndpoint? = null
    private var pairingEndpoint: DhdAdbEndpoint? = null

    val status: StateFlow<DeveloperModeStatus> = _status.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return
        }
        if (isPaired()) {
            beginMaintenanceProbe()
        } else {
            publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = false,
                message = PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        maintenanceProbeJob?.cancel()
        maintenanceMonitorJob?.cancel()
        maintenanceRecoveryJob?.cancel()
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        DhdAdbPairingNotification.cancel(appContext)
        appContext.stopService(Intent(appContext, DhdAdbPairingService::class.java))
    }

    /** Re-check the local ADB advertisement after the user changes settings. */
    fun refresh() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        when (_status.value.state) {
            DeveloperConnectionState.CONNECTING,
            DeveloperConnectionState.CHECKING,
            DeveloperConnectionState.PAIRING_SEARCHING,
            DeveloperConnectionState.PAIRING_SERVICE_FOUND,
            DeveloperConnectionState.UNSUPPORTED,
            -> Unit
            DeveloperConnectionState.READY,
            DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            DeveloperConnectionState.ERROR,
            -> if (isPaired()) beginMaintenanceProbe() else publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = false,
                message = PAIRING_REQUIRED_MESSAGE,
            )
            DeveloperConnectionState.PAIRING_REQUIRED -> publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = isPaired(),
                message = if (isPaired()) MAINTENANCE_RESTART_MESSAGE else PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    /** Start the Shizuku-style pairing notification while Settings is open. */
    fun startPairingNotification(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return false
        }
        if (!DhdAdbPairingNotification.areNotificationsEnabled(appContext)) {
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "Allow DHD notifications to enter the pairing code from the notification.",
            )
            return false
        }
        if (!started.get()) start()
        return runCatching {
            ContextCompat.startForegroundService(
                appContext,
                DhdAdbPairingService.startIntent(appContext),
            )
            true
        }.getOrElse { error ->
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "DHD could not start the pairing notification: ${rootMessage(error)}",
            )
            false
        }
    }

    /** Reset the controller for a fresh pairing attempt without changing the saved key. */
    internal fun preparePairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.get()) start()
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        maintenanceProbeJob?.cancel()
        maintenanceMonitorJob?.cancel()
        maintenanceRecoveryJob?.cancel()
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        publish(
            DeveloperConnectionState.PAIRING_SEARCHING,
            paired = isPaired(),
            message = PAIRING_SEARCHING_MESSAGE,
        )
        DhdAdbPairingNotification.showSearching(appContext)
        beginPairingDiscovery(pairingCode = null)
    }

    /** Stop a notification-owned pairing attempt and resume normal reconnecting. */
    internal fun cancelPairing() {
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        mdns.stop()
        endpoint = null
        pairingEndpoint = null
        if (isPaired()) {
            beginMaintenanceProbe()
        } else {
            publish(
                DeveloperConnectionState.PAIRING_REQUIRED,
                paired = false,
                message = PAIRING_REQUIRED_MESSAGE,
            )
        }
    }

    /**
     * Start one pairing attempt. The user should first open Developer options
     * and choose Wireless debugging → Pair device with pairing code.
     */
    fun pair(pairingCode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish(
                DeveloperConnectionState.UNSUPPORTED,
                paired = false,
                message = "Direct Wireless Debugging requires Android 11 or newer.",
            )
            return
        }
        if (!pairingCode.matches(PAIRING_CODE_REGEX)) {
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "Enter the six-digit Wireless Debugging pairing code.",
            )
            return
        }
        if (!started.get()) start()
        val discoveredPairingEndpoint = pairingEndpoint
        pairingJob?.cancel()
        discoveryTimeoutJob?.cancel()
        mdns.stop()
        publish(
            DeveloperConnectionState.CONNECTING,
            paired = isPaired(),
            message = if (discoveredPairingEndpoint == null) {
                "Looking for the Wireless Debugging pairing service…"
            } else {
                "Pairing with Wireless Debugging…"
            },
        )

        if (discoveredPairingEndpoint != null) {
            pairingJob = scope.launch { performPairing(discoveredPairingEndpoint, pairingCode) }
        } else {
            beginPairingDiscovery(pairingCode)
        }
    }

    internal suspend fun execute(command: List<String>, binaryOutput: Boolean = false): PhoneProcessResult =
        commandMutex.withLock {
            if (!started.get() || !isPaired()) return@withLock unavailableResult()
            try {
                val result = maintenanceBootstrap.client().execute(command, binaryOutput)
                if (result.exitCode == null && !result.timedOut) {
                    handleMaintenanceUnavailable(result.stderr)
                }
                result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleMaintenanceUnavailable(rootMessage(error))
                PhoneProcessResult(
                    exitCode = null,
                    stdout = ByteArray(0),
                    stderr = if (error is SocketTimeoutException) {
                        "The DHD maintenance command timed out."
                    } else {
                        "DHD maintenance service is unavailable: ${rootMessage(error)}"
                    },
                    timedOut = error is SocketTimeoutException,
                )
            }
        }

    private fun beginMaintenanceProbe() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isPaired()) return
        maintenanceProbeJob?.cancel()
        maintenanceProbeJob = scope.launch {
            if (maintenanceBootstrap.client().isCompatible()) {
                publishReady()
            } else {
                scheduleMaintenanceRecovery()
            }
        }
    }

    private fun startMaintenanceMonitor() {
        maintenanceMonitorJob?.cancel()
        maintenanceMonitorJob = scope.launch {
            while (isActive && started.get()) {
                delay(MAINTENANCE_HEALTH_INTERVAL_MS)
                if (!maintenanceBootstrap.client().isCompatible()) {
                    handleMaintenanceUnavailable("DHD's maintenance service stopped.")
                    return@launch
                }
            }
        }
    }

    private fun publishReady() {
        if (!started.get()) return
        publish(
            DeveloperConnectionState.READY,
            paired = true,
            message = "DHD maintenance service is running. Wireless Debugging can be turned off until DHD needs a restart.",
        )
        startMaintenanceMonitor()
    }

    private fun handleMaintenanceUnavailable(detail: String) {
        if (!started.get() || !isPaired()) return
        maintenanceMonitorJob?.cancel()
        publish(
            DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
            paired = true,
            message = "$detail Turn on Wireless debugging in Developer options to restart it. Pairing is already saved.",
        )
        scheduleMaintenanceRecovery()
    }

    private fun scheduleMaintenanceRecovery() {
        if (!started.get() || !isPaired() || maintenanceRecoveryJob?.isActive == true) return
        publish(
            DeveloperConnectionState.CONNECTING,
            paired = true,
            message = "Restarting DHD's maintenance service…",
        )
        maintenanceRecoveryJob = scope.launch {
            val recovered = commandMutex.withLock {
                val currentEndpoint = endpoint ?: return@withLock false
                try {
                    bootstrapMaintenance(currentEndpoint)
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "Maintenance service recovery failed", error)
                    false
                }
            }
            if (!started.get()) return@launch
            if (recovered) {
                publishReady()
            } else {
                endpoint = null
                beginConnectDiscovery()
            }
        }
    }

    private suspend fun bootstrapMaintenance(discovered: DhdAdbEndpoint) {
        DhdAdbClient(
            host = discovered.host,
            port = discovered.port,
            key = key,
        ).use { adb ->
            adb.connect()
            maintenanceBootstrap.ensureStarted(adb)
        }
    }

    private fun beginConnectDiscovery() {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        discoveryTimeoutJob?.cancel()
        pairingJob?.cancel()
        pairingEndpoint = null
        endpoint = null
        mdns.stop()
        publish(
            DeveloperConnectionState.CONNECTING,
            paired = isPaired(),
            message = "Waiting for Wireless Debugging…",
        )

        val completed = AtomicBoolean(false)
        discoveryTimeoutJob = scope.launch {
            delay(CONNECT_DISCOVERY_TIMEOUT_MS)
            if (completed.compareAndSet(false, true)) {
                mdns.stop()
                publish(
                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
                    paired = isPaired(),
                    message = WIRELESS_DEBUGGING_MESSAGE,
                )
            }
        }
        mdns.start(
            serviceType = DhdAdbMdns.TLS_CONNECT,
            onResolved = { discovered ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                endpoint = discovered
                scope.launch { verifyConnection(discovered) }
            },
            onLost = {
                if (started.get() && endpoint != null) beginConnectDiscovery()
            },
            onError = { error ->
                if (completed.compareAndSet(false, true)) {
                    discoveryTimeoutJob?.cancel()
                    mdns.stop()
                    publish(
                        DeveloperConnectionState.ERROR,
                        paired = isPaired(),
                        message = "Could not search for Wireless Debugging: ${rootMessage(error)}",
                    )
                }
            },
        )
    }

    private suspend fun verifyConnection(discovered: DhdAdbEndpoint) = commandMutex.withLock {
        if (endpoint != discovered) return@withLock
        publish(
            DeveloperConnectionState.CONNECTING,
            paired = isPaired(),
            message = "Connecting to DHD's local ADB service…",
        )
        var adbConnected = false
        try {
            DhdAdbClient(discovered.host, discovered.port, key).use { adb ->
                adb.connect()
                adbConnected = true
                maintenanceBootstrap.ensureStarted(adb)
            }
            publishReady()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (adbConnected) {
                publish(
                    DeveloperConnectionState.WIRELESS_DEBUGGING_OFF,
                    paired = true,
                    message = "DHD's maintenance service could not start. Turn on Wireless debugging in Developer options to retry; pairing is still saved.",
                )
            } else {
                mdns.stop()
                endpoint = null
                preferences.edit().putBoolean(KEY_PAIRED, false).apply()
                publish(
                    DeveloperConnectionState.PAIRING_REQUIRED,
                    paired = false,
                    message = "DHD is not authorized by Wireless Debugging. Pair DHD once, then it will reconnect automatically.",
                )
            }
        }
    }

    private suspend fun performPairing(endpoint: DhdAdbEndpoint, pairingCode: String) {
        Log.i(TAG, "Starting ADB pairing against local port ${endpoint.port}")
        try {
            val success = DhdAdbPairingClient(
                host = endpoint.host,
                port = endpoint.port,
                pairingCode = pairingCode,
                key = key,
            ).use { it.start() }
            if (!success) {
                pairingEndpoint = null
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = "DHD could not complete pairing. Check that the code is current and try again.",
                )
                return
            }
            pairingEndpoint = null
            preferences.edit().putBoolean(KEY_PAIRED, true).apply()
            beginConnectDiscovery()
        } catch (error: DhdAdbInvalidPairingCodeException) {
            pairingEndpoint = null
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = "That Wireless Debugging pairing code was not accepted.",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            pairingEndpoint = null
            Log.e(TAG, "Pairing failed: ${error::class.java.name}", error)
            publish(
                DeveloperConnectionState.ERROR,
                paired = isPaired(),
                message = pairingFailureMessage(error),
            )
        }
    }

    private fun beginPairingDiscovery(pairingCode: String?) {
        if (!started.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        discoveryTimeoutJob?.cancel()
        mdns.stop()
        pairingEndpoint = null

        val completed = AtomicBoolean(false)
        // Keep the first notification alive while the user navigates through
        // Developer options. Shizuku also keeps mDNS pairing discovery alive
        // until the service is found or the user stops the flow. A timeout is
        // still useful after a code has been submitted because that is a
        // bounded network operation rather than a UI-guided search.
        if (pairingCode != null) {
            discoveryTimeoutJob = scope.launch {
                delay(PAIRING_DISCOVERY_TIMEOUT_MS)
                if (!completed.compareAndSet(false, true)) return@launch
                mdns.stop()
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = "The Wireless Debugging pairing service was not found. Open Pair device with pairing code and try again.",
                )
            }
        } else {
            discoveryTimeoutJob = null
        }
        mdns.start(
            serviceType = DhdAdbMdns.TLS_PAIRING,
            onResolved = { discovered ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                pairingEndpoint = discovered
                if (pairingCode == null) {
                    publish(
                        DeveloperConnectionState.PAIRING_SERVICE_FOUND,
                        paired = isPaired(),
                        message = PAIRING_SERVICE_FOUND_MESSAGE,
                    )
                } else {
                    pairingJob = scope.launch { performPairing(discovered, pairingCode) }
                }
            },
            onError = { error ->
                if (!completed.compareAndSet(false, true)) return@start
                discoveryTimeoutJob?.cancel()
                mdns.stop()
                val message = "Could not search for Wireless Debugging: ${rootMessage(error)}"
                if (pairingCode == null) {
                    DhdAdbPairingNotification.showSearching(appContext)
                }
                publish(
                    DeveloperConnectionState.ERROR,
                    paired = isPaired(),
                    message = message,
                )
            },
        )
    }

    private fun unavailableResult(): PhoneProcessResult = PhoneProcessResult(
        exitCode = null,
        stdout = ByteArray(0),
        stderr = _status.value.message,
    )

    private fun isPaired(): Boolean = preferences.getBoolean(KEY_PAIRED, false)

    private fun publish(
        state: DeveloperConnectionState,
        paired: Boolean,
        message: String,
    ) {
        if (state == DeveloperConnectionState.READY) {
            DhdAdbPairingNotification.cancel(appContext)
        }
        _status.value = DeveloperModeStatus(state = state, paired = paired, message = message)
    }

    private fun rootMessage(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName

    private fun pairingFailureMessage(error: Throwable): String = when {
        containsCause(error) {
            it is UnsatisfiedLinkError ||
                it is ExceptionInInitializerError ||
                it is NoClassDefFoundError
        } -> "DHD's native pairing engine could not start. Reinstall the latest DHD build and try again."
        else -> "DHD pairing failed: ${rootMessage(error)}"
    }

    private fun containsCause(error: Throwable, predicate: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (predicate(current)) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val TAG = "DhdAdbController"
        const val PREFERENCES_NAME = "dhd_adb_connection"
        const val KEY_PAIRED = "paired"
        const val CONNECT_DISCOVERY_TIMEOUT_MS = 7_000L
        const val PAIRING_DISCOVERY_TIMEOUT_MS = 30_000L
        const val MAINTENANCE_HEALTH_INTERVAL_MS = 15_000L
        const val WIRELESS_DEBUGGING_MESSAGE =
            "Turn on Wireless debugging once to start DHD's maintenance service. Pairing is already saved."
        const val PAIRING_SEARCHING_MESSAGE =
            "Open Wireless debugging → Pair device with pairing code. DHD is listening for the pairing service."
        const val PAIRING_SERVICE_FOUND_MESSAGE =
            "The Wireless Debugging pairing service was found. Enter the six-digit code shown by Android."
        const val PAIRING_REQUIRED_MESSAGE =
            "Pair DHD once from Wireless debugging. After that, turn Wireless debugging on only when DHD needs a restart."
        const val MAINTENANCE_RESTART_MESSAGE =
            "DHD's maintenance service is not running. Turn on Wireless debugging in Developer options to restart it. Pairing is already saved."
        val PAIRING_CODE_REGEX = Regex("\\d{6}")
    }
}
